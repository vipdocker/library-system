package com.example.library.catalog;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.example.library.catalog.ShowApiClient.*;
import static org.assertj.core.api.Assertions.*;

/** Local fixtures only; the production address policy is exercised without any real upstream call. */
class ShowApiTransportTest {
    static final String KEY = "FAKE-TEST-KEY-000000";
    static final URI META = URI.create("https://route.showapi.com/1626-1?appKey=" + KEY);
    static final URI COVER_HTTPS = URI.create("https://static1.showapi.com/app2/isbn/imgs/a.jpg");
    static final URI COVER_HTTP = URI.create("http://static1.showapi.com/app2/isbn/imgs/a.png");
    static final byte[] FORM = "isbn=080442957X".getBytes(StandardCharsets.UTF_8);

    private static ShowApiTransport transport(ShowApiFixture fixture) throws Exception {
        return new ShowApiTransport(fixture.clientTls(), fixture.resolver(), address -> true);
    }

    @Test
    void t2MetadataPostIsFormEncodedAndCoverRequestsCarryNoCredentials() throws Exception {
        try (var fixture = new ShowApiFixture(); var transport = transport(fixture)) {
            var metadata = transport.exchange(META, "POST", FORM, Route.direct(), 256 * 1024, Duration.ofSeconds(3));
            assertThat(metadata.status()).isEqualTo(200);
            assertThat(fixture.requests.get(0)).startsWith("POST /1626-1?appKey=" + KEY + " HTTP/1.1");
            assertThat(fixture.requests.get(0).toLowerCase()).contains("content-type: application/x-www-form-urlencoded");
            assertThat(fixture.bodies.get(0)).isEqualTo("isbn=080442957X");
            assertThat(fixture.serverNames).contains("route.showapi.com");

            fixture.responseBody = BookCoverApiTest.image("png", 4, 6);
            fixture.contentType = "image/png";
            var httpsCover = transport.exchange(COVER_HTTPS, "GET", null, Route.direct(), 2097152, Duration.ofSeconds(3));
            assertThat(httpsCover.status()).isEqualTo(200);
            assertThat(new CoverImageValidator().validate(httpsCover.mediaType(), httpsCover.body()).width()).isEqualTo(4);
            var httpCover = transport.exchange(COVER_HTTP, "GET", null, Route.direct(), 2097152, Duration.ofSeconds(3));
            assertThat(httpCover.status()).isEqualTo(200);
            for (String request : fixture.requests) {
                var lower = request.toLowerCase();
                assertThat(lower).doesNotContain("cookie").doesNotContain("referer")
                        .doesNotContain("authorization").doesNotContain("x-xsrf");
            }
            assertThat(fixture.requests.get(1)).startsWith("GET /app2/isbn/imgs/a.jpg HTTP/1.1");
            assertThat(fixture.requests.get(1)).doesNotContain(KEY).doesNotContain("appKey");
            assertThat(fixture.requests.get(2)).startsWith("GET /app2/isbn/imgs/a.png HTTP/1.1");
            assertThat(fixture.serverNames).contains("static1.showapi.com");
            assertThat(fixture.failures).isEmpty();
            assertThat(transport.quiescence().toCompletableFuture()).isCompleted();
        }
    }

    @Test
    void t2NumericDirectoryCoversUseSameCredentialFreeTransport() throws Exception {
        String path = "/app2/isbn/532/88a6eae4990443aca573b0395844a8a3.jpg";
        try (var fixture = new ShowApiFixture(); var transport = transport(fixture)) {
            fixture.responseBody = BookCoverApiTest.image("jpeg", 5, 7);
            fixture.contentType = "image/jpeg";
            for (String scheme : new String[]{"http", "https"}) {
                var reply = transport.exchange(URI.create(scheme + "://static1.showapi.com" + path),
                        "GET", null, Route.direct(), 2097152, Duration.ofSeconds(3));
                assertThat(reply.status()).isEqualTo(200);
                assertThat(new CoverImageValidator().validate(reply.mediaType(), reply.body()).width()).isEqualTo(5);
            }
            assertThat(fixture.requests).hasSize(2);
            for (String request : fixture.requests) {
                assertThat(request).startsWith("GET " + path + " HTTP/1.1");
                assertThat(request.toLowerCase()).doesNotContain("appkey", "cookie", "authorization", "referer", "x-xsrf");
            }
            assertThat(fixture.failures).isEmpty();
        }
    }

    /**
     * Regression: a long-lived DNS UDP socket can silently stop receiving replies after a network/VPN
     * change (observed: only the startup UDP socket active, every lookup stalled ~3s until restart).
     * The fake server drops all queries from source ports it has already seen once retired; the
     * production resolver must still resolve, i.e. each lookup must use a fresh datagram channel.
     */
    @Test
    void t1DnsLookupsDoNotReuseAStaleLongLivedUdpSocket() throws Exception {
        try (var dns = new StaleFlowDnsServer()) {
            var group = ShowApiTransport.dnsResolver(new io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider(dns.address()));
            var loops = reactor.netty.resources.LoopResources.create("dns-stale-test", 1, true);
            try {
                var resolver = group.getResolver(loops.onClient(false).next());
                var first = resolver.resolveAll(InetSocketAddress.createUnresolved("first.example.test", 443));
                assertThat(first.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(first.isSuccess()).as("first lookup").isTrue();

                dns.retireSeenPorts();

                var second = resolver.resolveAll(InetSocketAddress.createUnresolved("second.example.test", 443));
                assertThat(second.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(second.isSuccess()).as("second lookup after the first socket went stale: %s", second.cause()).isTrue();
                assertThat(second.getNow()).extracting(InetSocketAddress::getHostString).contains("203.0.113.7");
            } finally {
                group.close();
                loops.disposeLater().block(Duration.ofSeconds(3));
            }
        }
    }

    /** Minimal UDP DNS responder: A -> 203.0.113.7, other types NOERROR/empty; drops retired source ports. */
    static final class StaleFlowDnsServer implements AutoCloseable {
        private final java.net.DatagramSocket socket = new java.net.DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        private final java.util.Set<Integer> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<Integer> retired = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Thread worker = new Thread(this::serve, "fake-dns");

        StaleFlowDnsServer() throws java.net.SocketException { worker.setDaemon(true); worker.start(); }

        InetSocketAddress address() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort()); }

        void retireSeenPorts() { retired.addAll(seen); }

        private void serve() {
            byte[] buffer = new byte[512];
            while (!socket.isClosed()) {
                try {
                    var packet = new java.net.DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    int port = packet.getPort();
                    if (retired.contains(port)) { continue; }
                    seen.add(port);
                    byte[] reply = answer(java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
                    if (reply != null) { socket.send(new java.net.DatagramPacket(reply, reply.length, packet.getSocketAddress())); }
                } catch (java.io.IOException ex) {
                    return;
                }
            }
        }

        private static byte[] answer(byte[] query) {
            if (query.length < 17) { return null; }
            int index = 12;
            while (index < query.length && query[index] != 0) { index += (query[index] & 0xff) + 1; }
            int questionEnd = index + 5;
            if (questionEnd > query.length) { return null; }
            int type = ((query[index + 1] & 0xff) << 8) | (query[index + 2] & 0xff);
            boolean a = type == 1;
            var out = new java.io.ByteArrayOutputStream();
            out.write(query[0]); out.write(query[1]);
            out.write(0x81); out.write(0x80);
            out.write(0); out.write(1);
            out.write(0); out.write(a ? 1 : 0);
            out.write(0); out.write(0);
            out.write(0); out.write(0);
            out.write(query, 12, questionEnd - 12);
            if (a) {
                out.writeBytes(new byte[]{(byte) 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, (byte) 203, 0, 113, 7});
            }
            return out.toByteArray();
        }

        @Override public void close() { socket.close(); }
    }

    @Test
    void t2RedirectsAreReturnedButNeverFollowed() throws Exception {
        try (var fixture = new ShowApiFixture(); var transport = transport(fixture)) {
            fixture.status = 302;
            fixture.location = "https://evil.example/steal";
            var reply = transport.exchange(COVER_HTTPS, "GET", null, Route.direct(), 2097152, Duration.ofSeconds(3));
            assertThat(reply.status()).isEqualTo(302);
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @Test
    void t1BoundedBodyAndDeadlineWithCleanup() throws Exception {
        try (var fixture = new ShowApiFixture(); var transport = transport(fixture)) {
            fixture.responseBody = new byte[100];
            assertThatThrownBy(() -> transport.exchange(META, "POST", FORM, Route.direct(), 16, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class,
                            failure -> assertThat(failure.reason).isEqualTo(FailureReason.BODY_TOO_LARGE));
            transport.quiescence().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);

            fixture.stallBody = true;
            fixture.responseBody = new byte[50];
            assertThatThrownBy(() -> transport.exchange(META, "POST", FORM, Route.direct(), 256 * 1024, Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(TransportFailure.class, failure -> {
                        assertThat(failure.reason).isEqualTo(FailureReason.TIMEOUT);
                        assertThat(failure.headersReceived).isTrue();
                        assertThat(failure.fallbackAllowed()).isFalse();
                    });
            transport.quiescence().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);
            fixture.stallBody = false;
            fixture.responseBody = "{\"showapi_res_code\":0}".getBytes(StandardCharsets.UTF_8);
            assertThat(transport.exchange(META, "POST", FORM, Route.direct(), 256 * 1024, Duration.ofSeconds(3)).status())
                    .isEqualTo(200);
        }
    }

    @Test
    void t2DirectDnsPrivateResultsAreRejectedBeforeAnyConnection() throws Exception {
        try (var fixture = new ShowApiFixture();
             var transport = new ShowApiTransport(fixture.clientTls(), fixture.resolver(), ShowApiTransport::publicAddress)) {
            assertThatThrownBy(() -> transport.exchange(META, "POST", FORM, Route.direct(), 256 * 1024, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class, failure -> {
                        assertThat(failure.reason).isEqualTo(FailureReason.ADDRESS_REJECTED);
                        assertThat(failure.headersReceived).isFalse();
                        assertThat(failure.requestSent).isFalse();
                        assertThat(failure.fallbackAllowed()).isFalse();
                    });
            assertThat(fixture.requests).isEmpty();
            assertThat(fixture.socksRequests).isEmpty();
        }
    }

    @Test
    void t2PublicAddressPolicyCoversPrivateLoopbackLinkLocalAndSpecialRanges() throws Exception {
        for (String address : new String[]{"127.0.0.1", "::1", "10.1.2.3", "192.168.1.1", "172.16.0.1",
                "169.254.1.1", "0.0.0.0", "255.255.255.255", "100.64.1.1", "224.0.0.1", "192.0.0.1", "198.18.0.1"}) {
            assertThat(ShowApiTransport.publicAddress(InetAddress.getByName(address))).as(address).isFalse();
        }
        assertThat(ShowApiTransport.publicAddress(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(ShowApiTransport.publicAddress(InetAddress.getByName("fd12:3456::1"))).isFalse();
        assertThat(ShowApiTransport.publicAddress(InetAddress.getByName("::ffff:127.0.0.1"))).isFalse();
        assertThat(ShowApiTransport.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(ShowApiTransport.publicAddress(InetAddress.getByName("1.1.1.1"))).isTrue();
        assertThat(ShowApiTransport.publicAddress(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        assertThat(ShowApiTransport.publicAddress(null)).isFalse();
    }

    @Test
    void t2SocksRouteDelegatesUpstreamDnsToTheAdminProxy() throws Exception {
        try (var fixture = new ShowApiFixture(); var transport = transport(fixture)) {
            var route = Route.socks(new com.example.library.common.proxy.ProxySnapshot(true, "127.0.0.1", fixture.proxyPort(), 1, null));
            assertThat(transport.exchange(META, "POST", FORM, route, 256 * 1024, Duration.ofSeconds(3)).status()).isEqualTo(200);
            fixture.responseBody = BookCoverApiTest.image("png", 4, 6);
            fixture.contentType = "image/png";
            assertThat(transport.exchange(COVER_HTTP, "GET", null, route, 2097152, Duration.ofSeconds(3)).status()).isEqualTo(200);
            assertThat(fixture.socksRequests).containsExactly(
                    new ShowApiFixture.SocksRequest("route.showapi.com", 443),
                    new ShowApiFixture.SocksRequest("static1.showapi.com", 80));
            assertThat(fixture.serverNames).contains("route.showapi.com");
        }
    }

    @Test
    void t2ValidateRejectsEverythingOutsideTheFixedContract() throws Exception {
        var route = Route.direct();
        byte[] ok = FORM;
        Duration timeout = Duration.ofSeconds(3);
        List<URI> badMetadata = List.of(
                URI.create("http://route.showapi.com/1626-1?appKey=" + KEY),
                URI.create("https://route.showapi.com:8443/1626-1?appKey=" + KEY),
                URI.create("https://route.showapi.com.evil/1626-1?appKey=" + KEY),
                URI.create("https://evil.example/1626-1?appKey=" + KEY),
                URI.create("https://route.showapi.com/other?appKey=" + KEY),
                URI.create("https://route.showapi.com/1626-1"),
                URI.create("https://route.showapi.com/1626-1?appKey=" + KEY + "&extra=1"),
                URI.create("https://route.showapi.com/1626-1?appKey=" + KEY + "#frag"),
                URI.create("https://user@route.showapi.com/1626-1?appKey=" + KEY),
                URI.create("https://127.0.0.1/1626-1?appKey=" + KEY));
        for (URI uri : badMetadata) {
            assertThatThrownBy(() -> ShowApiTransport.validate(uri, "POST", ok, route, 256 * 1024, timeout))
                    .as(uri.toString()).isInstanceOf(TransportFailure.class);
        }
        List<URI> badCover = List.of(
                URI.create("https://static1.showapi.com/app2/isbn/imgs/../secret.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/imgs/%2e%2e.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/imgs/a.jpg?x=1"),
                URI.create("https://static1.showapi.com/app2/isbn/imgs/a.jpg#f"),
                URI.create("https://static1.showapi.com:8080/app2/isbn/imgs/a.jpg"),
                URI.create("http://static1.showapi.com:443/app2/isbn/imgs/a.jpg"),
                URI.create("https://static1.showapi.com/other/a.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/imgs/a.gif"),
                URI.create("https://static1.showapi.com/app2/isbn/imgs/.hidden.jpg"),
                URI.create("https://user@static1.showapi.com/app2/isbn/imgs/a.jpg"),
                URI.create("https://10.0.0.1/app2/isbn/imgs/a.jpg"),
                URI.create("https://static1.showapi.com.evil/app2/isbn/532/88a6eae4990443aca573b0395844a8a3.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/../88a6eae4990443aca573b0395844a8a3.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/%35%33%32/88a6eae4990443aca573b0395844a8a3.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/532/88a6eae4990443aca573b0395844a8a3.jpg?token=x"),
                URI.create("https://static1.showapi.com/app2/isbn/532/88a6eae4990443aca573b0395844a8a3.jpg#fragment"),
                URI.create("https://static1.showapi.com/app2/isbn/arbitrary/88a6eae4990443aca573b0395844a8a3.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/532/nested/88a6eae4990443aca573b0395844a8a3.jpg"),
                URI.create("https://static1.showapi.com/app2/isbn/532/88a6eae4990443aca573b0395844a8a3.gif"));
        for (URI uri : badCover) {
            assertThatThrownBy(() -> ShowApiTransport.validate(uri, "GET", null, route, 2097152, timeout))
                    .as(uri.toString()).isInstanceOf(TransportFailure.class);
        }
        // Wrong method, wrong body, oversized limits and overlong timeouts are rejected.
        assertThatThrownBy(() -> ShowApiTransport.validate(META, "GET", null, route, 256 * 1024, timeout)).isInstanceOf(TransportFailure.class);
        assertThatThrownBy(() -> ShowApiTransport.validate(META, "POST", "isbn=bad".getBytes(StandardCharsets.UTF_8), route, 256 * 1024, timeout)).isInstanceOf(TransportFailure.class);
        assertThatThrownBy(() -> ShowApiTransport.validate(META, "POST", ok, route, 256 * 1024 + 1, timeout)).isInstanceOf(TransportFailure.class);
        assertThatThrownBy(() -> ShowApiTransport.validate(META, "POST", ok, route, 256 * 1024, Duration.ofSeconds(7))).isInstanceOf(TransportFailure.class);
        assertThatThrownBy(() -> ShowApiTransport.validate(COVER_HTTPS, "GET", ok, route, 2097152, timeout)).isInstanceOf(TransportFailure.class);
        assertThatThrownBy(() -> ShowApiTransport.validate(COVER_HTTPS, "GET", null, new Route(RouteKind.SOCKS5, null, null), 2097152, timeout)).isInstanceOf(TransportFailure.class);
        // Accepted shapes.
        ShowApiTransport.validate(META, "POST", ok, route, 256 * 1024, timeout);
        ShowApiTransport.validate(COVER_HTTPS, "GET", null, route, 2097152, timeout);
        ShowApiTransport.validate(COVER_HTTP, "GET", null, route, 2097152, timeout);
    }

    @Test
    void t1CloseStopsFurtherExchanges() throws Exception {
        try (var fixture = new ShowApiFixture()) {
            var transport = transport(fixture);
            transport.close();
            assertThatThrownBy(() -> transport.exchange(META, "POST", FORM, Route.direct(), 256 * 1024, Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(TransportFailure.class,
                            failure -> assertThat(failure.reason).isEqualTo(FailureReason.CANCELLED));
            assertThat(fixture.requests).isEmpty();
        }
    }
}