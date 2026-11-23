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
    private final String metadataUserAgent;
    private final String assetUserAgent;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private volatile CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);
    static final String DEFAULT_USER_AGENT = "LibraryCatalog";

    ReactorNettyTransport() { this(DEFAULT_USER_AGENT, DEFAULT_USER_AGENT); }

    ReactorNettyTransport(String metadataUserAgent, String assetUserAgent) {
        this(platformTls(), dnsResolver(), metadataUserAgent, assetUserAgent);
    }

    ReactorNettyTransport(SslContext tls, AddressResolverGroup<InetSocketAddress> directResolver) {
        this(tls, directResolver, DEFAULT_USER_AGENT, DEFAULT_USER_AGENT);
    }

    ReactorNettyTransport(SslContext tls, AddressResolverGroup<InetSocketAddress> directResolver, String metadataUserAgent, String assetUserAgent) {
        this.directResolver = directResolver;
        this.metadataUserAgent = metadataUserAgent;
        this.assetUserAgent = assetUserAgent;
        timer = Schedulers.fromExecutorService(loops.onClient(false));
        base = HttpClient.newConnection().runOn(loops, false).protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .noProxy().disableRetry(true).followRedirect(false).compress(false).keepAlive(false).wiretap(false)
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
                    // Contact information (if configured) is sent to openlibrary.org only, never to covers/archive.
                    .headers(headers -> headers.set("User-Agent", "openlibrary.org".equals(uri.getHost()) ? metadataUserAgent : assetUserAgent))
                    .doOnChannelInit((observer, channel, address) -> attempt.track(channel))
                    .doOnRequest((request, connection) -> attempt.connected.set(true))
                    .doOnResponse((response, connection) -> attempt.responded(response.status().code(),
                            response.responseHeaders().get("Retry-After")))
                    .get().uri(uri.toASCIIString())
                    .response((response, content) -> {
                        Reply headersOnly = new Reply(response.status().code(), response.responseHeaders().get("Content-Type"),
                                response.responseHeaders().get("Retry-After"), response.responseHeaders().get("Location"), new byte[0]);
                        // Non-success bodies are never read: only status and safe headers are diagnostic evidence.
                        if (response.status().code() != 200) { return Mono.just(headersOnly); }
                        var body = new BoundedBody(maxBytes);
                        return content.doOnNext(body::accept).then(Mono.fromSupplier(() -> new Reply(headersOnly.status(),
                                headersOnly.mediaType(), headersOnly.retryAfter(), headersOnly.location(), body.bytes())));
                    }).single());
            long connectRemaining = Math.min(timeout.toNanos(), TimeUnit.SECONDS.toNanos(3)) - (System.nanoTime() - start);
            Mono<Reply> connectionLimit = Mono.delay(Duration.ofNanos(Math.max(1, connectRemaining)), timer)
                    .flatMap(ignored -> attempt.connected.get() ? Mono.never() : Mono.error(new TimeoutException(attempt.timeoutPhase().name())));
            CompletableFuture<Reply> result = Mono.firstWithSignal(operation, connectionLimit)
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), timer).toFuture();
            attempt.result(result);
            return result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException ex) {
            throw attempt.withHeaders(classify(ex.getCause(), attempt.headers.get(), attempt.stopped.get() || closed.get(), attempt.timeoutPhase()));
        } catch (TimeoutException ex) {
            throw attempt.withHeaders(new TransportFailure(attempt.timeoutPhase(), attempt.headers.get()));
        } catch (CancellationException ex) {
            throw attempt.withHeaders(new TransportFailure(FailureReason.CANCELLED, attempt.headers.get()));
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
        boolean metadata = permittedMetadataResource(uri) && maxBytes <= 256 * 1024;
        boolean cover = permittedCoverResource(uri);
        if (!metadata && !cover || route.kind() == RouteKind.SOCKS5 && (route.host() == null || route.port() == null
                || route.port() < 1 || route.port() > 65535)) { throw new TransportFailure(FailureReason.PROTOCOL, false); }
    }

    static TransportFailure classify(Throwable failure, boolean headers, boolean cancelled) {
        return classify(failure, headers, cancelled, FailureReason.TIMEOUT);
    }

    /** timeoutPhase classifies generic timeouts by how far the exchange got (TCP / handshake / response / body). */
    static TransportFailure classify(Throwable failure, boolean headers, boolean cancelled, FailureReason timeoutPhase) {
        if (cancelled) { return new TransportFailure(FailureReason.CANCELLED, headers); }
        var causes = new ArrayList<Throwable>();
        for (Throwable next = failure; next != null && !causes.contains(next); next = next.getCause()) { causes.add(next); }
        if (causes.stream().anyMatch(c -> c instanceof ProxyConnectException)) { return new TransportFailure(FailureReason.PROTOCOL, headers); }
        if (causes.stream().anyMatch(c -> c instanceof io.netty.handler.ssl.SslHandshakeTimeoutException)) { return new TransportFailure(FailureReason.HANDSHAKE_TIMEOUT, headers); }
        for (Throwable cause : causes) {
            if (cause instanceof SSLException || cause instanceof java.security.cert.CertificateException) { return new TransportFailure(FailureReason.TLS, headers); }
            if (cause instanceof BodyOverflow) { return new TransportFailure(FailureReason.BODY_TOO_LARGE, headers); }
        }
        for (Throwable cause : causes) {
            if (cause instanceof UnknownHostException) { return new TransportFailure(FailureReason.DNS, headers); }
            if (cause instanceof io.netty.channel.ConnectTimeoutException) { return new TransportFailure(FailureReason.TIMEOUT, headers); }
            if (cause instanceof TimeoutException && cause.getMessage() != null) {
                try { return new TransportFailure(FailureReason.valueOf(cause.getMessage()), headers); }
                catch (IllegalArgumentException ignored) { /* fall through to the phase */ }
            }
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) { return new TransportFailure(timeoutPhase, headers); }
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
        private volatile Integer status;
        private volatile String retryAfter;
        private final List<Channel> channels = new ArrayList<>();
        private CompletableFuture<Reply> result;
        void responded(int code, String retry) { status = code; retryAfter = retry; headers.set(true); }
        synchronized boolean tcpActive() { return channels.stream().anyMatch(Channel::isActive); }
        /** TCP connect TIMEOUT vs SOCKS/TLS HANDSHAKE_TIMEOUT vs RESPONSE_TIMEOUT (request sent) vs body TIMEOUT (headers seen). */
        FailureReason timeoutPhase() {
            if (headers.get()) { return FailureReason.TIMEOUT; }
            if (connected.get()) { return FailureReason.RESPONSE_TIMEOUT; }
            return tcpActive() ? FailureReason.HANDSHAKE_TIMEOUT : FailureReason.TIMEOUT;
        }
        TransportFailure withHeaders(TransportFailure failure) {
            return failure.headersReceived ? new TransportFailure(failure.reason, true, status, retryAfter) : failure;
        }
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
