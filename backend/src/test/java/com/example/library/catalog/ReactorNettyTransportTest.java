package com.example.library.catalog;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.library.catalog.OpenLibraryClient.*;

class ReactorNettyTransportTest {
    static final URI META = URI.create("https://openlibrary.org/api/books?bibkeys=ISBN:080442957X&jscmd=data&format=json");
    private static Route socks(SocksTlsFixture f) { return new Route(RouteKind.SOCKS5, "localhost", f.proxyPort()); }

    @Test
    void acProxy04RealJavaSocks5NoAuthDomainTlsMetadataAndCover() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            var metadata = transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(3));
            assertThat(metadata.status()).isEqualTo(200); assertThat(new String(metadata.body())).contains("fixture book");
            var cover = transport.get(URI.create("https://covers.openlibrary.org/b/isbn/080442957X-M.jpg?default=false"), socks(fixture), 2097152, Duration.ofSeconds(3));
            assertThat(new CoverImageValidator().validate(cover.mediaType(), cover.body()).width()).isEqualTo(4);
            assertThat(fixture.socksRequests).containsExactly(new SocksTlsFixture.SocksRequest(0, 3, "openlibrary.org", 443),
                    new SocksTlsFixture.SocksRequest(0, 3, "covers.openlibrary.org", 443));
            assertThat(fixture.serverNames).containsExactly("openlibrary.org", "covers.openlibrary.org");
            assertThat(fixture.httpRequests).allSatisfy(request -> {
                assertThat(request).contains("LibraryCatalog").doesNotContain("Cookie:", "X-XSRF-TOKEN:", "Accept-Encoding:", "Authorization:");
            });
            assertThat(fixture.failures).isEmpty(); assertThat(transport.quiescence().toCompletableFuture()).isCompleted();
        }
    }

    @Test
    void acProxy03RealDirectRouteNeverUsesSocksAndDoesNotFollowRedirect() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.responseStatus = 302;
            assertThat(transport.get(META, Route.direct(), 256 * 1024, Duration.ofSeconds(3)).status()).isEqualTo(302);
            assertThat(fixture.socksRequests).isEmpty(); assertThat(fixture.httpRequests).hasSize(1);
        }
    }

    @Test
    void acProxy06PlatformTrustRejectsFixtureCertificateWithoutFallbackClassification() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport()) {
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> {
                        assertThat(e.reason).isEqualTo(FailureReason.TLS); assertThat(e.fallbackAllowed()).isFalse();
                        assertThat(e.getCause()).isNull(); assertThat(e.getMessage()).isEqualTo("TLS");
                    });
            assertThat(fixture.socksRequests).hasSize(1); assertThat(fixture.httpRequests).isEmpty();
        }
    }

    @Test
    void acProxy06TrustedCertificateWithWrongHostnameStillFailsTls() throws Exception {
        try (var fixture = new SocksTlsFixture(true); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> { assertThat(e.reason).isEqualTo(FailureReason.TLS); assertThat(e.fallbackAllowed()).isFalse(); });
            assertThat(fixture.httpRequests).isEmpty();
        }
    }

    @Test
    void acProxy04SimulatedDirectDnsFailureUsesRealSocksForBothIndependentStages() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            var routes = new java.util.ArrayList<RouteKind>();
            var client = new OpenLibraryClient((RoutedTransport) (uri, route, max, timeout) -> {
                routes.add(route.kind());
                if (route.kind() == RouteKind.DIRECT) { throw new TransportFailure(FailureReason.DNS, false); }
                return transport.get(uri, route, max, timeout);
            }, System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator(),
                    () -> new com.example.library.common.proxy.ProxySnapshot(true, "localhost", fixture.proxyPort(), 0, null));
            var result = client.lookup("080442957X");
            assertThat(result.fields().title()).isEqualTo("fixture book"); assertThat(result.cover().width()).isEqualTo(4);
            assertThat(routes).containsExactly(RouteKind.DIRECT, RouteKind.SOCKS5, RouteKind.DIRECT, RouteKind.SOCKS5);
            assertThat(fixture.socksRequests).hasSize(2); assertThat(fixture.httpRequests).hasSize(2);
        }
    }

    @Test
    void acProxy06OverflowCancelsStreamingAndBodyTimeoutAfterHeaderNeverFallback() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.responseBody = new byte[256 * 1024 + 1];
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> { assertThat(e.reason).isEqualTo(FailureReason.BODY_TOO_LARGE); assertThat(e.headersReceived).isTrue(); });
            fixture.responseBody = new byte[100]; fixture.stallBody = true;
            long before = System.nanoTime();
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofMillis(500)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> { assertThat(e.reason).isEqualTo(FailureReason.TIMEOUT); assertThat(e.headersReceived).isTrue(); assertThat(e.fallbackAllowed()).isFalse(); });
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(1));
            transport.quiescence().toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void acProxy06NoAuthRefusalIsProtocolFailureAndNeverAuthenticates() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.rejectAuth = true;
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> assertThat(e.reason).isEqualTo(FailureReason.PROTOCOL));
            assertThat(fixture.socksRequests).hasSize(1); assertThat(fixture.httpRequests).isEmpty();
        }
    }

    @Test
    void acProxy06InvalidDestinationsAndNonPositiveBudgetNeverConnect() throws Exception {
        try (var transport = new ReactorNettyTransport()) {
            for (String url : List.of("http://openlibrary.org/api/books", "https://127.0.0.1/", "https://openlibrary.org:8443/", "https://u@openlibrary.org/", "https://openlibrary.org/evil")) {
                assertThatThrownBy(() -> transport.get(URI.create(url), Route.direct(), 256 * 1024, Duration.ofSeconds(1)))
                        .isInstanceOfSatisfying(TransportFailure.class, e -> assertThat(e.reason).isEqualTo(FailureReason.PROTOCOL));
            }
            assertThatThrownBy(() -> transport.get(META, Route.direct(), 256 * 1024, Duration.ZERO)).isInstanceOf(TransportFailure.class);
        }
    }

    @Test
    void acProxy06CombinedSocksAndTlsConnectionPhaseHasOneThreeSecondBudget() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.proxyHandshakeDelayMillis = 1700; fixture.tlsHandshakeDelayMillis = 1700;
            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(6)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> { assertThat(e.reason).isEqualTo(FailureReason.TIMEOUT); assertThat(e.headersReceived).isFalse(); });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3600));
        }
    }

    @Test
    void acProxy06CallerInterruptCancelsSubscriptionAndReclaimsChannel() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses());
             var executor = Executors.newSingleThreadExecutor()) {
            fixture.stallBody = true;
            var stopped = new CountDownLatch(1); var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var future = executor.submit(() -> {
                try { transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(6)); }
                catch (Throwable failure) { error.set(failure); }
                finally { stopped.countDown(); }
            });
            assertThat(fixture.bodyStarted.await(3, TimeUnit.SECONDS)).isTrue(); future.cancel(true);
            assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(error.get()).isInstanceOf(InterruptedException.class);
            transport.quiescence().toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void acProxy06InterruptAndApplicationCloseCancelOwnedConnectionsAndLoops() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses());
             var executor = Executors.newSingleThreadExecutor()) {
            fixture.stallBody = true;
            var result = executor.submit(() -> assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(6)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> assertThat(e.reason).isEqualTo(FailureReason.CANCELLED)));
            assertThat(fixture.bodyStarted.await(3, TimeUnit.SECONDS)).isTrue(); transport.close(); result.get(2, TimeUnit.SECONDS);
            assertThatThrownBy(() -> transport.get(META, Route.direct(), 256 * 1024, Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> assertThat(e.reason).isEqualTo(FailureReason.CANCELLED));
        }
    }
}
