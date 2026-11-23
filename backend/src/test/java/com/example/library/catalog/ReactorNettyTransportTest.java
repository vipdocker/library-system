package com.example.library.catalog;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.example.library.catalog.OpenLibraryClient.*;

class ReactorNettyTransportTest {
    /** OL-OPT-API revision: transport-level metadata fixture is the Edition JSON endpoint (Legacy /api/books removed). */
    static final URI META = URI.create("https://openlibrary.org/books/OL1M.json");
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
    void transportExposesLocationButNeverAutomaticallyFollowsApprovedOrRejectedCoverRedirects() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            URI cover = URI.create("https://covers.openlibrary.org/b/id/9273767-M.jpg?default=false");
            fixture.coverLocation = "https://archive.org/download/m_covers_0009/m_covers_0009_27.zip/0009273767-M.jpg";
            var approved = transport.get(cover, Route.direct(), 2097152, Duration.ofSeconds(3));
            assertThat(approved.status()).isEqualTo(302); assertThat(approved.location()).isEqualTo(fixture.coverLocation);
            assertThat(fixture.httpRequests).hasSize(1); assertThat(fixture.socksRequests).isEmpty();
            fixture.coverLocation = "https://archive.org.evil/secret"; fixture.coverRedirectStatus = 301;
            var rejected = transport.get(cover, Route.direct(), 2097152, Duration.ofSeconds(3));
            assertThat(rejected.status()).isEqualTo(301); assertThat(rejected.location()).isEqualTo(fixture.coverLocation);
            assertThat(fixture.httpRequests).hasSize(2); assertThat(fixture.socksRequests).isEmpty();
            assertThat(fixture.serverNames).containsExactly("covers.openlibrary.org", "covers.openlibrary.org");
        }
    }

    @Test
    void approvedCoverRedirectUsesRealSocksAndTlsWithoutRepeatingDirectProbe() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.responseBody = "{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"title\":\"fixture book\",\"covers\":[9273767]}".getBytes();
            fixture.coverLocation = "https://archive.org/download/m_covers_0009/m_covers_0009_27.zip/0009273767-M.jpg";
            var routes = new java.util.ArrayList<RouteKind>();
            var client = new OpenLibraryClient((RoutedTransport) (uri, route, max, timeout) -> {
                routes.add(route.kind());
                if (route.kind() == RouteKind.DIRECT) { throw new TransportFailure(FailureReason.DNS, false); }
                return transport.get(uri, route, max, timeout);
            }, System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator(),
                    () -> new com.example.library.common.proxy.ProxySnapshot(true, "localhost", fixture.proxyPort(), 1, null));
            var result = client.lookup("080442957X");
            assertThat(result.fields().title()).isEqualTo("fixture book");
            assertThat(result.cover()).isNotNull();
            assertThat(result.cover().width()).isEqualTo(4);
            assertThat(result.warnings()).isEmpty();
            // OL-OPT-API revision: /isbn redirect hop and archive hop are both pinned to the SOCKS route that answered.
            assertThat(routes).containsExactly(RouteKind.DIRECT, RouteKind.SOCKS5, RouteKind.SOCKS5, RouteKind.DIRECT, RouteKind.SOCKS5, RouteKind.SOCKS5);
            assertThat(fixture.socksRequests).extracting(SocksTlsFixture.SocksRequest::domain)
                    .containsExactly("openlibrary.org", "openlibrary.org", "covers.openlibrary.org", "archive.org");
            assertThat(fixture.serverNames).containsExactly("openlibrary.org", "openlibrary.org", "covers.openlibrary.org", "archive.org");
            assertThat(fixture.httpRequests).hasSize(4);
            assertThat(fixture.failures).isEmpty();
            assertThat(transport.quiescence().toCompletableFuture()).isCompleted();
        }
    }

    @Test
    void apiRealTlsFlowUsesOnlyEditionSearchCoverAndWorkEndpointsInOrder() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses(),
                "LibraryCatalog/9.9.9 (ops@example.org)", "LibraryCatalog/9.9.9")) {
            fixture.responseBody = ("{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"title\":\"fixture book\",\"covers\":[9273767],"
                    + "\"authors\":[{\"key\":\"/authors/OL1A\"}],\"works\":[{\"key\":\"/works/OL1W\"}]}").getBytes();
            var client = new OpenLibraryClient(transport, System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator());
            var result = client.lookup("080442957X");
            assertThat(result.fields().title()).isEqualTo("fixture book"); assertThat(result.fields().author()).isEqualTo("Fixture Author");
            assertThat(result.fields().description()).isEqualTo("fixture work description"); assertThat(result.cover().width()).isEqualTo(4);
            assertThat(result.warnings()).extracting(w -> w.code()).containsExactly("DESCRIPTION_FROM_WORK");
            assertThat(fixture.httpRequests).extracting(request -> request.substring(0, request.indexOf(" HTTP/1.1"))).containsExactly(
                    "GET /isbn/080442957X.json", "GET /books/OL1M.json",
                    "GET /search.json?q=isbn:080442957X&fields=key,author_key,author_name&limit=1",
                    "GET /b/id/9273767-M.jpg?default=false", "GET /works/OL1W.json");
            assertThat(fixture.httpRequests.get(3)).doesNotContain("ops@example.org");
            assertThat(List.of(0, 1, 2, 4)).allSatisfy(i -> assertThat(fixture.httpRequests.get(i)).contains("(ops@example.org)"));
            assertThat(fixture.socksRequests).isEmpty(); assertThat(fixture.failures).isEmpty();
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
            assertThat(routes).containsExactly(RouteKind.DIRECT, RouteKind.SOCKS5, RouteKind.SOCKS5, RouteKind.DIRECT, RouteKind.SOCKS5);
            assertThat(fixture.socksRequests).hasSize(3); assertThat(fixture.httpRequests).hasSize(3);
            assertThat(fixture.httpRequests.get(0)).startsWith("GET /isbn/080442957X.json ");
            assertThat(fixture.httpRequests.get(1)).startsWith("GET /books/OL1M.json ");
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
    void diagNonSuccessResponseBodyIsNeverReadButStatusAndRetryAfterSurvive() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.responseStatus = 503; fixture.retryAfter = "120"; fixture.responseBody = new byte[256 * 1024 + 10];
            var reply = transport.get(META, Route.direct(), 256 * 1024, Duration.ofSeconds(3));
            assertThat(reply.status()).isEqualTo(503); assertThat(reply.retryAfter()).isEqualTo("120");
            assertThat(reply.body()).isEmpty();
        }
    }

    @Test
    void diagBodyFailureAfterHeadersPreservesStatusAndRetryAfter() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.stallBody = true; fixture.retryAfter = "5";
            assertThatThrownBy(() -> transport.get(META, Route.direct(), 256 * 1024, Duration.ofMillis(600)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> {
                        assertThat(e.headersReceived).isTrue(); assertThat(e.status).isEqualTo(200); assertThat(e.retryAfter).isEqualTo("5");
                    });
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
    void approvedArchivePathsPassTransportGuardButOtherArchiveDestinationsFailClosed() throws Exception {
        String archive = "https://archive.org/download/m_covers_0009/m_covers_0009_27.zip/0009273767-M.jpg";
        assertThatCode(() -> ReactorNettyTransport.validate(URI.create(archive), Route.direct(), 2097152, Duration.ofSeconds(3))).doesNotThrowAnyException();
        assertThatCode(() -> ReactorNettyTransport.validate(URI.create("https://covers.openlibrary.org/b/id/9273767-M.jpg"), Route.direct(), 2097152, Duration.ofSeconds(3))).doesNotThrowAnyException();
        for (String url : List.of(archive + "?token=secret", archive + "#fragment", archive.replace("https:", "http:"),
                archive.replace("archive.org", "archive.org.evil"), archive.replace("archive.org", "u@archive.org"),
                archive.replace("archive.org", "127.0.0.1"), archive.replace("archive.org", "ia800001.us.archive.org"),
                archive.replace("_27.zip", "_28.zip"), archive.replace("-M.jpg", "-L.jpg"),
                archive.replace("download", "%64ownload"), "https://archive.org/download/other/cover.jpg")) {
            assertThatThrownBy(() -> ReactorNettyTransport.validate(URI.create(url), Route.direct(), 2097152, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> assertThat(e.reason).isEqualTo(FailureReason.PROTOCOL));
        }
    }

    @Test
    void apiTransportGuardAllowsOnlyExactEditionSearchWorkCoverAndArchiveResources() {
        for (String ok : List.of("https://openlibrary.org/isbn/080442957X.json", "https://openlibrary.org/isbn/9780804429573.json",
                "https://openlibrary.org/books/OL7353617M.json", "https://openlibrary.org/works/OL45804W.json",
                "https://openlibrary.org/search.json?q=isbn:080442957X&fields=key,author_key,author_name&limit=1")) {
            assertThatCode(() -> ReactorNettyTransport.validate(URI.create(ok), Route.direct(), 256 * 1024, Duration.ofSeconds(3))).as(ok).doesNotThrowAnyException();
            assertThatThrownBy(() -> ReactorNettyTransport.validate(URI.create(ok), Route.direct(), 256 * 1024 + 1, Duration.ofSeconds(3))).isInstanceOf(TransportFailure.class);
        }
        for (String bad : List.of("https://openlibrary.org/api/books?bibkeys=ISBN:080442957X&jscmd=data&format=json",
                "https://openlibrary.org/search.json?q=isbn:080442957X&fields=*&limit=1", "https://openlibrary.org/authors/OL1A.json",
                "https://openlibrary.org/works/OL1W/editions.json", "https://openlibrary.org/books/OL1M.json?x=1", "https://openlibrary.org/isbn/%30.json",
                "https://openlibrary.org/books/OL1M.json#f", "https://openlibrary.org:8443/books/OL1M.json")) {
            assertThatThrownBy(() -> ReactorNettyTransport.validate(URI.create(bad), Route.direct(), 256 * 1024, Duration.ofSeconds(3)))
                    .as(bad).isInstanceOfSatisfying(TransportFailure.class, e -> assertThat(e.reason).isEqualTo(FailureReason.PROTOCOL));
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
        // OL-OPT-POLICY revision: once TCP is up, a stalled SOCKS/TLS handshake is HANDSHAKE_TIMEOUT (never learned), not TCP TIMEOUT.
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.proxyHandshakeDelayMillis = 1700; fixture.tlsHandshakeDelayMillis = 1700;
            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.get(META, socks(fixture), 256 * 1024, Duration.ofSeconds(6)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> { assertThat(e.reason).isEqualTo(FailureReason.HANDSHAKE_TIMEOUT); assertThat(e.headersReceived).isFalse(); });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3600));
        }
    }

    @Test
    void policyDirectTlsHandshakeStallIsHandshakeTimeoutAndHeaderStallIsResponseTimeout() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.tlsHandshakeDelayMillis = 3500;
            assertThatThrownBy(() -> transport.get(META, Route.direct(), 256 * 1024, Duration.ofSeconds(6)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> { assertThat(e.reason).isEqualTo(FailureReason.HANDSHAKE_TIMEOUT); assertThat(e.learnable()).isFalse(); });
            transport.quiescence().toCompletableFuture().get(2, TimeUnit.SECONDS);
            fixture.tlsHandshakeDelayMillis = 0; fixture.stallHeaders = true;
            assertThatThrownBy(() -> transport.get(META, Route.direct(), 256 * 1024, Duration.ofMillis(800)))
                    .isInstanceOfSatisfying(TransportFailure.class, e -> {
                        assertThat(e.reason).isEqualTo(FailureReason.RESPONSE_TIMEOUT); assertThat(e.headersReceived).isFalse();
                        assertThat(e.fallbackAllowed()).isTrue(); assertThat(e.learnable()).isFalse();
                    });
        }
    }

    @Test
    void policyContactOnlyReachesOpenLibraryMetadataHostNeverCoversOrArchive() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses(),
                "LibraryCatalog/9.9.9 (ops@example.org)", "LibraryCatalog/9.9.9")) {
            transport.get(META, Route.direct(), 256 * 1024, Duration.ofSeconds(3));
            transport.get(URI.create("https://covers.openlibrary.org/b/id/9273767-M.jpg?default=false"), Route.direct(), 2097152, Duration.ofSeconds(3));
            transport.get(URI.create("https://archive.org/download/m_covers_0009/m_covers_0009_27.zip/0009273767-M.jpg"), Route.direct(), 2097152, Duration.ofSeconds(3));
            assertThat(fixture.httpRequests).hasSize(3);
            assertThat(fixture.httpRequests.get(0)).contains("User-Agent: LibraryCatalog/9.9.9 (ops@example.org)");
            assertThat(fixture.httpRequests.subList(1, 3)).allSatisfy(request ->
                    assertThat(request).contains("User-Agent: LibraryCatalog/9.9.9").doesNotContain("@", "ops"));
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
