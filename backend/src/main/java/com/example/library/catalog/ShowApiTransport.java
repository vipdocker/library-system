package com.example.library.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientSecurityUtils;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ProxyProvider;

import static com.example.library.catalog.ShowApiClient.*;

/**
 * Independent ShowAPI transport (showapi-design-v1 §6). Metadata: HTTPS POST to
 * route.showapi.com:443/1626-1 only. Covers: static1.showapi.com /app2/isbn/ legacy imgs or
 * numeric/hash JPEG/PNG paths over HTTP 80 or HTTPS 443. No redirects, retries, wiretap or compression. Direct DNS
 * results are checked against a public-address policy and the connection uses exactly those
 * checked results (anti-rebind); SOCKS goes through the admin proxy trust boundary. Cover requests
 * carry no AppKey, Cookie, CSRF, Authorization or Referer headers.
 */
final class ShowApiTransport implements Transport {
    private static final Pattern COVER_PATH =
            Pattern.compile("/app2/isbn/(?:imgs/[A-Za-z0-9][A-Za-z0-9._-]*|[0-9]{1,6}/[a-f0-9]{32})\\.(?:jpg|jpeg|png)");
    private final LoopResources loops = LoopResources.create("library-showapi", 1, true);
    private final AddressResolverGroup<InetSocketAddress> proxyResolver = dnsResolver();
    private final AddressResolverGroup<InetSocketAddress> directResolver;
    private final AddressResolverGroup<InetSocketAddress> checkedDirect;
    private final Scheduler timer;
    private final HttpClient httpsBase;
    private final HttpClient httpBase;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private volatile CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);

    ShowApiTransport() { this(platformTls(), dnsResolver(), ShowApiTransport::publicAddress); }

    ShowApiTransport(SslContext tls, AddressResolverGroup<InetSocketAddress> directResolver, Predicate<InetAddress> addressPolicy) {
        this.directResolver = directResolver;
        this.checkedDirect = checked(directResolver, addressPolicy);
        timer = Schedulers.fromExecutorService(loops.onClient(false));
        httpsBase = base().secure(spec -> spec.sslContext(tls)
                .handlerConfigurator(HttpClientSecurityUtils.HOSTNAME_VERIFICATION_CONFIGURER)
                .handshakeTimeout(Duration.ofSeconds(3)).closeNotifyFlushTimeout(Duration.ZERO).closeNotifyReadTimeout(Duration.ZERO));
        httpBase = base();
    }

    private HttpClient base() {
        return HttpClient.newConnection().runOn(loops, false).protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .noProxy().disableRetry(true).followRedirect(false).compress(false).keepAlive(false).wiretap(false)
                .headers(headers -> headers.set("User-Agent", "LibraryCatalog/0.1 (local library; ShowAPI ISBN lookup)"));
    }

    private static AddressResolverGroup<InetSocketAddress> dnsResolver() {
        return dnsResolver(DnsServerAddressStreamProviders.platformDefault());
    }

    static AddressResolverGroup<InetSocketAddress> dnsResolver(DnsServerAddressStreamProvider nameServers) {
        return new DnsAddressResolverGroup(new DnsNameResolverBuilder().channelType(NioDatagramChannel.class)
                .socketChannelType(NioSocketChannel.class).queryTimeoutMillis(3000).nameServerProvider(nameServers));
    }

    private static SslContext platformTls() {
        try { return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build(); }
        catch (SSLException ex) { throw new IllegalStateException("unable to initialize upstream TLS"); }
    }

    @Override
    public Reply exchange(URI uri, String method, byte[] form, Route route, int maxBytes, Duration timeout)
            throws IOException, InterruptedException {
        if (closed.get()) { throw new TransportFailure(FailureReason.CANCELLED, false, false); }
        validate(uri, method, form, route, maxBytes, timeout);
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        var attempt = new Attempt();
        if (!active.compareAndSet(null, attempt)) { throw new TransportFailure(FailureReason.PROTOCOL, false, false); }
        cleanup = attempt.cleaned;
        try {
            HttpClient selected = "https".equals(uri.getScheme()) ? httpsBase : httpBase;
            Mono<HttpClient> routed = route.kind() == RouteKind.DIRECT
                    ? Mono.just(selected.noProxy().resolver(checkedDirect))
                    : resolveProxy(route).map(address -> selected.resolver(NoopAddressResolverGroup.INSTANCE)
                        .proxy(proxy -> proxy.type(ProxyProvider.Proxy.SOCKS5).address(address)
                                .connectTimeoutMillis(Math.max(1, Math.min(3000, timeout.toMillis())))));
            Mono<Reply> operation = routed.flatMap(http -> {
                HttpClient prepared = http
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.max(1, Math.min(3000, timeout.toMillis())))
                        .doOnChannelInit((observer, channel, address) -> attempt.track(channel))
                        .doOnRequest((request, connection) -> { attempt.connected.set(true); attempt.sent.set(true); })
                        .doOnResponse((response, connection) -> attempt.headers.set(true));
                HttpClient.ResponseReceiver<?> receiver = "POST".equals(method)
                        ? prepared.headers(h -> h.set("Content-Type", "application/x-www-form-urlencoded")
                                .set("Content-Length", String.valueOf(form.length)))
                            .post().uri(uri.toASCIIString()).send(Mono.just(Unpooled.wrappedBuffer(form)))
                        : prepared.get().uri(uri.toASCIIString());
                return receiver.response((response, content) -> {
                    var body = new BoundedBody(maxBytes);
                    return content.doOnNext(body::accept).then(Mono.fromSupplier(() -> new Reply(response.status().code(),
                            response.responseHeaders().get("Content-Type"), response.responseHeaders().get("Retry-After"),
                            body.bytes())));
                }).single();
            });
            long connectRemaining = Math.min(timeout.toNanos(), TimeUnit.SECONDS.toNanos(3)) - (System.nanoTime() - start);
            Mono<Reply> connectionLimit = Mono.delay(Duration.ofNanos(Math.max(1, connectRemaining)), timer)
                    .flatMap(ignored -> attempt.connected.get() ? Mono.never() : Mono.error(new TimeoutException()));
            CompletableFuture<Reply> result = Mono.firstWithSignal(operation, connectionLimit)
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), timer).toFuture();
            attempt.result(result);
            return result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException ex) {
            throw classify(ex.getCause(), attempt.headers.get(), attempt.sent.get(), attempt.stopped.get() || closed.get());
        } catch (TimeoutException ex) {
            throw new TransportFailure(FailureReason.TIMEOUT, attempt.headers.get(), attempt.sent.get());
        } catch (CancellationException ex) {
            throw new TransportFailure(FailureReason.CANCELLED, attempt.headers.get(), attempt.sent.get());
        } finally {
            attempt.stop();
            attempt.cleaned.whenComplete((ignored, error) -> active.compareAndSet(attempt, null));
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try { attempt.cleaned.get(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (ExecutionException | TimeoutException ignored) { }
            }
        }
    }

    private Mono<InetSocketAddress> resolveProxy(Route route) {
        return Mono.create(sink -> {
            var resolver = proxyResolver.getResolver(loops.onClient(false).next());
            var future = resolver.resolve(InetSocketAddress.createUnresolved(route.host(), route.port()));
            sink.onCancel(() -> future.cancel(false));
            future.addListener(done -> {
                if (done.isSuccess()) { sink.success(future.getNow()); }
                else if (!done.isCancelled()) { sink.error(done.cause()); }
            });
        });
    }

    static void validate(URI uri, String method, byte[] form, Route route, int maxBytes, Duration timeout) throws TransportFailure {
        if (uri == null || method == null || route == null || route.kind() == null || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(6)) > 0
                || maxBytes < 1 || maxBytes > CoverImageValidator.MAX_BYTES
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) {
            throw new TransportFailure(FailureReason.PROTOCOL, false, false);
        }
        int port = uri.getPort();
        String scheme = uri.getScheme();
        boolean metadata = "POST".equals(method) && "https".equals(scheme) && "route.showapi.com".equals(uri.getHost())
                && (port == -1 || port == 443) && "/1626-1".equals(uri.getRawPath())
                && uri.getRawQuery() != null && uri.getRawQuery().matches("appKey=[A-Za-z0-9._~%+-]{1,1536}")
                && maxBytes <= 256 * 1024 && form != null
                && new String(form, StandardCharsets.UTF_8).matches("isbn=[0-9X]{10,13}");
        boolean cover = "GET".equals(method) && "static1.showapi.com".equals(uri.getHost())
                && ("https".equals(scheme) && (port == -1 || port == 443) || "http".equals(scheme) && (port == -1 || port == 80))
                && uri.getRawQuery() == null && uri.getRawPath() != null && !uri.getRawPath().contains("%")
                && COVER_PATH.matcher(uri.getRawPath()).matches() && (form == null || form.length == 0);
        if (!metadata && !cover || route.kind() == RouteKind.SOCKS5 && (route.host() == null || route.port() == null
                || route.port() < 1 || route.port() > 65535)) {
            throw new TransportFailure(FailureReason.PROTOCOL, false, false);
        }
    }

    /** Public-address policy for direct DNS results; the SOCKS proxy is a separate admin trust boundary. */
    static boolean publicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) { return false; }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) { return false; } // IPv6 unique-local fc00::/7
            boolean mapped = true;
            for (int i = 0; i < 10; i++) { mapped &= bytes[i] == 0; }
            mapped &= (bytes[10] & 255) == 255 && (bytes[11] & 255) == 255;
            if (mapped) {
                try { return publicAddress(InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]})); }
                catch (UnknownHostException ex) { return false; }
            }
            return true;
        }
        int first = bytes[0] & 255, second = bytes[1] & 255;
        if (first == 0 || first == 255) { return false; }
        if (first == 100 && second >= 64 && second <= 127) { return false; } // CGNAT 100.64.0.0/10
        if (first == 192 && second == 0 && (bytes[2] & 255) == 0) { return false; } // 192.0.0.0/24
        if (first == 198 && (second == 18 || second == 19)) { return false; } // benchmarking 198.18.0.0/15
        return true;
    }

    /** Wraps the delegate resolver so the connection uses exactly the checked resolution (anti-rebind). */
    private static AddressResolverGroup<InetSocketAddress> checked(AddressResolverGroup<InetSocketAddress> delegate,
                                                                   Predicate<InetAddress> policy) {
        return new AddressResolverGroup<>() {
            @Override
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                AddressResolver<InetSocketAddress> inner = delegate.getResolver(executor);
                return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                    @Override
                    protected boolean doIsResolved(InetSocketAddress address) {
                        return address.getAddress() != null && policy.test(address.getAddress());
                    }
                    @Override
                    protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                        inner.resolve(address).addListener((Future<InetSocketAddress> done) -> {
                            if (!done.isSuccess()) { promise.setFailure(done.cause()); }
                            else {
                                InetSocketAddress resolved = done.getNow();
                                if (resolved.getAddress() == null || !policy.test(resolved.getAddress())) {
                                    promise.setFailure(new AddressRejectedException());
                                } else { promise.setSuccess(resolved); }
                            }
                        });
                    }
                    @Override
                    protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                        inner.resolveAll(address).addListener((Future<List<InetSocketAddress>> done) -> {
                            if (!done.isSuccess()) { promise.setFailure(done.cause()); }
                            else {
                                List<InetSocketAddress> resolved = done.getNow();
                                if (resolved.isEmpty() || resolved.stream()
                                        .anyMatch(a -> a.getAddress() == null || !policy.test(a.getAddress()))) {
                                    promise.setFailure(new AddressRejectedException());
                                } else { promise.setSuccess(resolved); }
                            }
                        });
                    }
                };
            }
        };
    }

    static final class AddressRejectedException extends IOException { }

    static TransportFailure classify(Throwable failure, boolean headers, boolean sent, boolean cancelled) {
        if (cancelled) { return new TransportFailure(FailureReason.CANCELLED, headers, sent); }
        var causes = new ArrayList<Throwable>();
        for (Throwable next = failure; next != null && !causes.contains(next); next = next.getCause()) { causes.add(next); }
        if (causes.stream().anyMatch(c -> c instanceof AddressRejectedException)) {
            return new TransportFailure(FailureReason.ADDRESS_REJECTED, headers, sent);
        }
        if (causes.stream().anyMatch(c -> c instanceof ProxyConnectException)) { return new TransportFailure(FailureReason.PROTOCOL, headers, sent); }
        if (causes.stream().anyMatch(c -> c instanceof io.netty.handler.ssl.SslHandshakeTimeoutException)) { return new TransportFailure(FailureReason.TIMEOUT, headers, sent); }
        for (Throwable cause : causes) {
            if (cause instanceof SSLException || cause instanceof java.security.cert.CertificateException) { return new TransportFailure(FailureReason.TLS, headers, sent); }
            if (cause instanceof BodyOverflow) { return new TransportFailure(FailureReason.BODY_TOO_LARGE, headers, sent); }
        }
        for (Throwable cause : causes) {
            if (cause instanceof UnknownHostException) { return new TransportFailure(FailureReason.DNS, headers, sent); }
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException || cause instanceof io.netty.channel.ConnectTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return new TransportFailure(FailureReason.TIMEOUT, headers, sent);
            }
            if (cause instanceof NoRouteToHostException) { return new TransportFailure(FailureReason.NO_ROUTE, headers, sent); }
            if (cause instanceof ConnectException) { return new TransportFailure(FailureReason.CONNECT, headers, sent); }
            if (cause instanceof PrematureCloseException || cause instanceof ClosedChannelException
                    || cause instanceof SocketException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connection reset")) {
                return new TransportFailure(FailureReason.RESET, headers, sent);
            }
        }
        if (causes.stream().anyMatch(c -> c instanceof DecoderException)) { return new TransportFailure(FailureReason.PROTOCOL, headers, sent); }
        return new TransportFailure(FailureReason.NETWORK, headers, sent);
    }

    static final class BodyOverflow extends RuntimeException { }
    static final class BoundedBody {
        private final int max;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        BoundedBody(int max) { this.max = max; }
        void accept(ByteBuf buffer) {
            int count = buffer.readableBytes();
            if (count > max - data.size()) { throw new BodyOverflow(); }
            byte[] chunk = new byte[count];
            buffer.getBytes(buffer.readerIndex(), chunk);
            data.writeBytes(chunk);
        }
        byte[] bytes() { return data.toByteArray(); }
    }

    private static final class Attempt {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicBoolean headers = new AtomicBoolean();
        final AtomicBoolean sent = new AtomicBoolean();
        final AtomicBoolean stopped = new AtomicBoolean();
        final CompletableFuture<Void> cleaned = new CompletableFuture<>();
        private final List<Channel> channels = new ArrayList<>();
        private CompletableFuture<Reply> result;
        synchronized void track(Channel channel) {
            channels.add(channel);
            if (stopped.get()) { channel.close(); }
        }
        synchronized void result(CompletableFuture<Reply> future) {
            result = future;
            if (stopped.get()) { future.cancel(true); }
        }
        synchronized void stop() {
            if (!stopped.compareAndSet(false, true)) { return; }
            if (result != null) { result.cancel(true); }
            var closures = new ArrayList<CompletableFuture<Void>>();
            for (Channel channel : channels) {
                var done = new CompletableFuture<Void>();
                closures.add(done);
                channel.close().addListener(closed -> done.complete(null));
            }
            CompletableFuture.allOf(closures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> cleaned.complete(null));
        }
    }

    @Override
    public CompletionStage<Void> quiescence() { return cleanup; }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        Attempt attempt = active.get();
        if (attempt != null) { attempt.stop(); }
        checkedDirect.close();
        proxyResolver.close();
        directResolver.close();
        loops.disposeLater(Duration.ZERO, Duration.ofSeconds(2)).block(Duration.ofSeconds(3));
    }
}