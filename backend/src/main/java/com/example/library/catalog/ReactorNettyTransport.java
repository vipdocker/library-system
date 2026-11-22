package com.example.library.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientSecurityUtils;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ProxyProvider;
import static com.example.library.catalog.OpenLibraryClient.*;

final class ReactorNettyTransport implements RoutedTransport {
    private final LoopResources loops = LoopResources.create("library-open-library", 1, true);
    private final AddressResolverGroup<InetSocketAddress> proxyResolver = dnsResolver();
    private final AddressResolverGroup<InetSocketAddress> directResolver;
    private final Scheduler timer;
    private final HttpClient base;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private volatile CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);

    ReactorNettyTransport() { this(platformTls(), dnsResolver()); }

    ReactorNettyTransport(SslContext tls, AddressResolverGroup<InetSocketAddress> directResolver) {
        this.directResolver = directResolver;
        timer = Schedulers.fromExecutorService(loops.onClient(false));
        base = HttpClient.newConnection().runOn(loops, false).protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .noProxy().disableRetry(true).followRedirect(false).compress(false).keepAlive(false).wiretap(false)
                .headers(headers -> headers.set("User-Agent", "LibraryCatalog/0.1 (local library; Open Library ISBN lookup)"))
                .secure(spec -> spec.sslContext(tls).handlerConfigurator(HttpClientSecurityUtils.HOSTNAME_VERIFICATION_CONFIGURER)
                        .handshakeTimeout(Duration.ofSeconds(3)).closeNotifyFlushTimeout(Duration.ZERO).closeNotifyReadTimeout(Duration.ZERO));
    }

    private static AddressResolverGroup<InetSocketAddress> dnsResolver() {
        return new DnsAddressResolverGroup(new DnsNameResolverBuilder().channelType(NioDatagramChannel.class)
                .socketChannelType(NioSocketChannel.class).queryTimeoutMillis(3000));
    }

    private static SslContext platformTls() {
        try { return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build(); }
        catch (SSLException ex) { throw new IllegalStateException("unable to initialize upstream TLS"); }
    }

    @Override
    public Reply get(URI uri, Route route, int maxBytes, Duration timeout) throws IOException, InterruptedException {
        if (closed.get()) { throw new TransportFailure(FailureReason.CANCELLED, false); }
        validate(uri, route, maxBytes, timeout);
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        var attempt = new Attempt();
        if (!active.compareAndSet(null, attempt)) { throw new TransportFailure(FailureReason.PROTOCOL, false); }
        cleanup = attempt.cleaned;
        try {
            Mono<HttpClient> routed = route.kind() == RouteKind.DIRECT
                    ? Mono.just(base.noProxy().resolver(directResolver))
                    : resolveProxy(route).map(address -> base.resolver(NoopAddressResolverGroup.INSTANCE)
                        .proxy(proxy -> proxy.type(ProxyProvider.Proxy.SOCKS5).address(address)
                                .connectTimeoutMillis(Math.max(1, Math.min(3000, timeout.toMillis())))));
            Mono<Reply> operation = routed.flatMap(http -> http
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.max(1, Math.min(3000, timeout.toMillis())))
                    .doOnChannelInit((observer, channel, address) -> attempt.track(channel))
                    .doOnRequest((request, connection) -> attempt.connected.set(true))
                    .doOnResponse((response, connection) -> attempt.headers.set(true))
                    .get().uri(uri.toASCIIString())
                    .response((response, content) -> {
                        var body = new BoundedBody(maxBytes);
                        return content.doOnNext(body::accept).then(Mono.fromSupplier(() -> new Reply(response.status().code(),
                                response.responseHeaders().get("Content-Type"), response.responseHeaders().get("Retry-After"), body.bytes())));
                    }).single());
            long connectRemaining = Math.min(timeout.toNanos(), TimeUnit.SECONDS.toNanos(3)) - (System.nanoTime() - start);
            Mono<Reply> connectionLimit = Mono.delay(Duration.ofNanos(Math.max(1, connectRemaining)), timer)
                    .flatMap(ignored -> attempt.connected.get() ? Mono.never() : Mono.error(new TimeoutException()));
            CompletableFuture<Reply> result = Mono.firstWithSignal(operation, connectionLimit)
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), timer).toFuture();
            attempt.result(result);
            return result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException ex) {
            throw classify(ex.getCause(), attempt.headers.get(), attempt.stopped.get() || closed.get());
        } catch (TimeoutException ex) {
            throw new TransportFailure(FailureReason.TIMEOUT, attempt.headers.get());
        } catch (CancellationException ex) {
            throw new TransportFailure(FailureReason.CANCELLED, attempt.headers.get());
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

    static void validate(URI uri, Route route, int maxBytes, Duration timeout) throws TransportFailure {
        if (uri == null || route == null || route.kind() == null || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(6)) > 0 || maxBytes < 1 || maxBytes > CoverImageValidator.MAX_BYTES
                || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new TransportFailure(FailureReason.PROTOCOL, false);
        }
        boolean metadata = "openlibrary.org".equals(uri.getHost()) && "/api/books".equals(uri.getRawPath())
                && uri.getRawQuery() != null && uri.getRawQuery().matches("bibkeys=ISBN:[0-9X]{10,13}&jscmd=data&format=json")
                && maxBytes <= 256 * 1024;
        boolean cover = "covers.openlibrary.org".equals(uri.getHost()) && "default=false".equals(uri.getRawQuery())
                && uri.getRawPath().matches("/b/(?:isbn/[0-9X]{10,13}|id/[1-9][0-9]{0,18})-M\\.jpg");
        if (!metadata && !cover || route.kind() == RouteKind.SOCKS5 && (route.host() == null || route.port() == null
                || route.port() < 1 || route.port() > 65535)) { throw new TransportFailure(FailureReason.PROTOCOL, false); }
    }

    static TransportFailure classify(Throwable failure, boolean headers, boolean cancelled) {
        if (cancelled) { return new TransportFailure(FailureReason.CANCELLED, headers); }
        var causes = new ArrayList<Throwable>();
        for (Throwable next = failure; next != null && !causes.contains(next); next = next.getCause()) { causes.add(next); }
        if (causes.stream().anyMatch(c -> c instanceof ProxyConnectException)) { return new TransportFailure(FailureReason.PROTOCOL, headers); }
        if (causes.stream().anyMatch(c -> c instanceof io.netty.handler.ssl.SslHandshakeTimeoutException)) { return new TransportFailure(FailureReason.TIMEOUT, headers); }
        for (Throwable cause : causes) {
            if (cause instanceof SSLException || cause instanceof java.security.cert.CertificateException) { return new TransportFailure(FailureReason.TLS, headers); }
            if (cause instanceof BodyOverflow) { return new TransportFailure(FailureReason.BODY_TOO_LARGE, headers); }
        }
        for (Throwable cause : causes) {
            if (cause instanceof UnknownHostException) { return new TransportFailure(FailureReason.DNS, headers); }
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException || cause instanceof io.netty.channel.ConnectTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) { return new TransportFailure(FailureReason.TIMEOUT, headers); }
            if (cause instanceof NoRouteToHostException) { return new TransportFailure(FailureReason.NO_ROUTE, headers); }
            if (cause instanceof ConnectException) { return new TransportFailure(FailureReason.CONNECT, headers); }
            if (cause instanceof PrematureCloseException || cause instanceof ClosedChannelException
                    || cause instanceof SocketException && cause.getMessage() != null && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connection reset")) {
                return new TransportFailure(FailureReason.RESET, headers);
            }
        }
        if (causes.stream().anyMatch(c -> c instanceof DecoderException || c instanceof ProxyConnectException)) {
            return new TransportFailure(FailureReason.PROTOCOL, headers);
        }
        return new TransportFailure(FailureReason.NETWORK, headers);
    }

    static final class BodyOverflow extends RuntimeException { }
    static final class BoundedBody {
        private final int max;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        BoundedBody(int max) { this.max = max; }
        void accept(ByteBuf buffer) {
            int count = buffer.readableBytes();
            if (count > max - data.size()) { throw new BodyOverflow(); }
            byte[] chunk = new byte[count]; buffer.getBytes(buffer.readerIndex(), chunk); data.writeBytes(chunk);
        }
        byte[] bytes() { return data.toByteArray(); }
    }

    private static final class Attempt {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicBoolean headers = new AtomicBoolean();
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
                var done = new CompletableFuture<Void>(); closures.add(done);
                channel.close().addListener(closed -> done.complete(null));
            }
            CompletableFuture.allOf(closures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> cleaned.complete(null));
        }
    }

    @Override public CompletionStage<Void> quiescence() { return cleanup; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        Attempt attempt = active.get(); if (attempt != null) { attempt.stop(); }
        proxyResolver.close(); directResolver.close();
        // The timer borrows the event-loop executor; only LoopResources owns its shutdown.
        loops.disposeLater(Duration.ZERO, Duration.ofSeconds(2)).block(Duration.ofSeconds(3));
    }
}
