package com.example.library.catalog;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.example.library.catalog.dto.ShowApiLookupResponse;
import com.example.library.catalog.settings.ShowApiSettings;
import com.example.library.catalog.settings.ShowApiSettingsProvider;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.proxy.ProxySnapshot;
import com.example.library.common.proxy.ProxySnapshotProvider;
import org.junit.jupiter.api.Test;

import static com.example.library.catalog.ShowApiClient.*;
import static org.assertj.core.api.Assertions.*;

/** Fake credentials only ("FAKE-TEST-KEY-000000"); no real ShowAPI call is ever made here. */
class ShowApiClientTest {
    static final String KEY = "FAKE-TEST-KEY-000000";
    static final String ISBN10 = "080442957X";
    static final String ISBN13 = "9780804429573";
    static final ShowApiSettingsProvider ENABLED = () -> new ShowApiSettings(true, KEY, 7, null);
    static final ProxySnapshotProvider NO_PROXY = () -> new ProxySnapshot(false, null, null, 0, null);
    static final ProxySnapshotProvider PROXY = () -> new ProxySnapshot(true, "127.0.0.1", 1080, 1, null);

    record Sent(URI uri, String method, byte[] body, Route route, int maxBytes, Duration timeout) { }

    static class FakeTransport implements Transport {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final List<Reply> replies = new ArrayList<>();
        final List<TransportFailure> failures = new ArrayList<>();
        int calls;
        @Override public Reply exchange(URI uri, String method, byte[] body, Route route, int maxBytes, Duration timeout)
                throws TransportFailure {
            sent.add(new Sent(uri, method, body, route, maxBytes, timeout));
            int index = calls++;
            if (index < failures.size() && failures.get(index) != null) { throw failures.get(index); }
            return replies.get(index);
        }
        @Override public CompletionStage<Void> quiescence() { return CompletableFuture.completedFuture(null); }
    }

    static Reply json(String body) { return new Reply(200, "application/json", null, body.getBytes(StandardCharsets.UTF_8)); }
    static String envelope(String data) {
        return "{\"showapi_res_code\":0,\"showapi_res_error\":\"\",\"showapi_res_body\":{\"ret_code\":0,\"remark\":\"ok\",\"data\":" + data + "}}";
    }
    static String data(String isbn, String extra) {
        return "{\"isbn\":\"" + isbn + "\",\"title\":\"候选书名\",\"author\":\"作者\",\"publisher\":\"出版社\","
                + "\"pubdate\":\"2020-05\",\"gist\":\"简介内容\",\"edition\":\"2版\",\"page\":\"300页\",\"format\":\"16开\","
                + "\"paper\":\"胶版纸\",\"binding\":\"平装\",\"price\":\"39.80元\",\"produce\":\"不映射\","
                + "\"img\":\"https://static1.showapi.com/app2/isbn/imgs/book.jpg\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static ShowApiClient client(FakeTransport transport, AtomicLong time, ShowApiSettingsProvider config, ProxySnapshotProvider proxy) {
        return new ShowApiClient(transport, time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator(), proxy, config);
    }

    @Test
    void acS2SuccessMapsOnlyDocumentedFieldsWithFixedSourceUrlAndValidatedCover() throws Exception {
        var transport = new FakeTransport();
        transport.replies.add(json(envelope(data(ISBN10, ""))));
        byte[] png = BookCoverApiTest.image("png", 4, 6);
        transport.replies.add(new Reply(200, "image/png", null, png));
        var time = new AtomicLong(1_000_000_000L);
        var response = client(transport, time, ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(response.provider()).isEqualTo("SHOWAPI");
        assertThat(response.isbn()).isEqualTo(ISBN10);
        assertThat(response.found()).isTrue();
        assertThat(response.sourceUrl()).isEqualTo("https://www.showapi.com/apis/isbn-book-1626");
        assertThat(response.fields().title()).isEqualTo("候选书名");
        assertThat(response.fields().author()).isEqualTo("作者");
        assertThat(response.fields().publisher()).isEqualTo("出版社");
        assertThat(response.fields().description()).isEqualTo("简介内容");
        assertThat(response.fields().publishYear()).isEqualTo(2020);
        assertThat(response.additional().publicationDate()).isEqualTo("2020-05");
        assertThat(response.additional().edition()).isEqualTo("2版");
        assertThat(response.additional().pageCountText()).isEqualTo("300页");
        assertThat(response.additional().format()).isEqualTo("16开");
        assertThat(response.additional().paper()).isEqualTo("胶版纸");
        assertThat(response.additional().binding()).isEqualTo("平装");
        assertThat(response.additional().priceText()).isEqualTo("39.80元");
        assertThat(response.capabilities().tableOfContents()).isFalse();
        assertThat(response.cover().mediaType()).isEqualTo("image/png");
        assertThat(response.cover().base64()).isEqualTo(Base64.getEncoder().encodeToString(png));
        assertThat(response.cover().byteLength()).isEqualTo(png.length);
        assertThat(response.cover().width()).isEqualTo(4);
        assertThat(response.cover().height()).isEqualTo(6);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.retryAfterSeconds()).isNull();
        assertThat(transport.sent).hasSize(2);
        var metadata = transport.sent.get(0);
        assertThat(metadata.method()).isEqualTo("POST");
        assertThat(metadata.uri().toString()).isEqualTo("https://route.showapi.com/1626-1?appKey=" + KEY);
        assertThat(new String(metadata.body(), StandardCharsets.UTF_8)).isEqualTo("isbn=" + ISBN10);
        assertThat(metadata.maxBytes()).isEqualTo(256 * 1024);
        assertThat(metadata.timeout()).isLessThanOrEqualTo(Duration.ofSeconds(6));
        var cover = transport.sent.get(1);
        assertThat(cover.method()).isEqualTo("GET");
        assertThat(cover.uri().toString()).isEqualTo("https://static1.showapi.com/app2/isbn/imgs/book.jpg");
        assertThat(cover.uri().toString()).doesNotContain(KEY);
        assertThat(cover.body()).isNull();
    }

    @Test
    void acS2IsbnMustMatchOrBeStrictlyEquivalentOtherwiseNoCoverOutbound() throws Exception {
        for (String[] pair : new String[][]{{ISBN10, ISBN13}, {ISBN13, ISBN10}, {ISBN10, ISBN10}}) {
            var transport = new FakeTransport();
            transport.replies.add(json(envelope(data(pair[1], ""))));
            transport.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
            var response = client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(pair[0]);
            assertThat(response.found()).isTrue();
            assertThat(response.isbn()).isEqualTo(pair[0]);
        }
        for (String bad : new String[]{null, "\"123\"", "\"9787115428028\"", "123"}) {
            var transport = new FakeTransport();
            String dataJson = bad == null ? data(ISBN10, "").replace("\"isbn\":\"" + ISBN10 + "\",", "")
                    : data(ISBN10, "").replace("\"isbn\":\"" + ISBN10 + "\"", "\"isbn\":" + bad);
            transport.replies.add(json(envelope(dataJson)));
            var client = client(transport, new AtomicLong(1), ENABLED, NO_PROXY);
            assertThatThrownBy(() -> client.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_INVALID_RESPONSE));
            assertThat(transport.sent).hasSize(1);
        }
    }

    @Test
    void acS2EnvelopeAndBusinessFailuresAreDistinct() throws Exception {
        var transport = new FakeTransport();
        transport.replies.add(json(envelope("{\"isbn\":\"" + ISBN10 + "\",\"ret_code\":1}")));
        // ret_code lives in showapi_res_body; build a proper nonzero business response:
        transport.replies.set(0, json("{\"showapi_res_code\":0,\"showapi_res_body\":{\"ret_code\":-1,\"remark\":\"内部 remark 不透传\"}}"));
        var response = client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(response.found()).isFalse();
        assertThat(response.fields().title()).isNull();
        assertThat(response.cover()).isNull();
        assertThat(response.warnings()).hasSize(1);
        assertThat(response.warnings().get(0).message()).contains("查询未成功或未收录");
        assertThat(response.warnings().get(0).message()).doesNotContain("remark");

        String[] invalid = {"not json", "[]", "{}", "{\"showapi_res_code\":\"0\"}",
                "{\"showapi_res_code\":0}", "{\"showapi_res_code\":0,\"showapi_res_body\":{}}",
                "{\"showapi_res_code\":0,\"showapi_res_body\":{\"ret_code\":0}}",
                "{\"showapi_res_code\":0,\"showapi_res_body\":{\"ret_code\":0,\"data\":[]}}"};
        for (String body : invalid) {
            var t = new FakeTransport();
            t.replies.add(json(body));
            var c = client(t, new AtomicLong(1), ENABLED, NO_PROXY);
            assertThatThrownBy(() -> c.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_INVALID_RESPONSE));
        }
        var system = new FakeTransport();
        system.replies.add(json("{\"showapi_res_code\":-1,\"showapi_res_error\":\"secret remark\",\"showapi_res_body\":{}}"));
        var c = client(system, new AtomicLong(1), ENABLED, NO_PROXY);
        assertThatThrownBy(() -> c.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_UNAVAILABLE);
            assertThat(ex.getMessage()).doesNotContain("secret");
            assertThat(ex.getDetails().toString()).doesNotContain("secret");
        });
    }

    @Test
    void acS2FieldHygieneNullsWarningsAndNoSilentTruncation() throws Exception {
        String longTitle = "t".repeat(201);
        String longGist = "g".repeat(2001);
        String longDate = "d".repeat(65);
        String extra = "\"title\":\"" + longTitle + "\",\"author\":42,\"gist\":\"" + longGist + "\",\"price\":\"\"";
        var transport = new FakeTransport();
        transport.replies.add(json(envelope(data(ISBN10, extra))));
        transport.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        var response = client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(response.fields().title()).isNull();
        assertThat(response.fields().author()).isNull();
        assertThat(response.fields().description()).isNull();
        assertThat(response.additional().priceText()).isNull();
        assertThat(response.warnings()).extracting(ShowApiLookupResponse.Warning::code)
                .contains("FIELD_TOO_LONG", "FIELD_INVALID");
        assertThat(response.warnings()).extracting(ShowApiLookupResponse.Warning::field)
                .contains("title", "author", "description");

        var ambiguous = new FakeTransport();
        ambiguous.replies.add(json(envelope(data(ISBN10, "\"pubdate\":\"2020年2021印\""))));
        ambiguous.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        var r2 = client(ambiguous, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(r2.additional().publicationDate()).isEqualTo("2020年2021印");
        assertThat(r2.fields().publishYear()).isNull();
        assertThat(r2.warnings()).extracting(ShowApiLookupResponse.Warning::code).contains("YEAR_AMBIGUOUS");

        var tooLongDate = new FakeTransport();
        tooLongDate.replies.add(json(envelope(data(ISBN10, "\"pubdate\":\"" + longDate + "\""))));
        tooLongDate.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        var r3 = client(tooLongDate, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(r3.additional().publicationDate()).isNull();
        assertThat(r3.fields().publishYear()).isNull();
    }

    @Test
    void t2OnlySafeStaticCoverUrlsAreFetched() throws Exception {
        String[] rejected = {
                "https://static1.showapi.com.evil/app2/isbn/imgs/a.jpg",
                "https://evil.example/app2/isbn/imgs/a.jpg",
                "http://static1.showapi.com:8080/app2/isbn/imgs/a.jpg",
                "https://static1.showapi.com:444/app2/isbn/imgs/a.jpg",
                "https://static1.showapi.com/app2/isbn/imgs/../secret.jpg",
                "https://static1.showapi.com/app2/isbn/imgs/%2e%2e/secret.jpg",
                "https://static1.showapi.com/app2/isbn/imgs/a.jpg?token=x",
                "https://static1.showapi.com/app2/isbn/imgs/a.jpg#frag",
                "https://user:pass@static1.showapi.com/app2/isbn/imgs/a.jpg",
                "https://127.0.0.1/app2/isbn/imgs/a.jpg",
                "ftp://static1.showapi.com/app2/isbn/imgs/a.jpg",
                "https://static1.showapi.com/other/a.jpg",
                "https://static1.showapi.com/app2/isbn/imgs/.hidden.jpg",
                "https://static1.showapi.com/app2/isbn/imgs/a.gif",
        };
        for (String url : rejected) {
            var transport = new FakeTransport();
            transport.replies.add(json(envelope(data(ISBN10, "\"img\":\"" + url + "\""))));
            var response = client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
            assertThat(response.cover()).as(url).isNull();
            assertThat(response.found()).as(url).isTrue();
            assertThat(response.warnings()).extracting(ShowApiLookupResponse.Warning::code).contains("COVER_REFERENCE_REJECTED");
            assertThat(transport.sent).as(url).hasSize(1);
        }
        // Documented plain-HTTP cover is allowed; do not claim HTTPS-only.
        var http = new FakeTransport();
        http.replies.add(json(envelope(data(ISBN10, "\"img\":\"http://static1.showapi.com/app2/isbn/imgs/a.jpeg\""))));
        http.replies.add(new Reply(200, "image/jpeg", null, BookCoverApiTest.image("jpeg", 5, 7)));
        var response = client(http, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(response.cover().mediaType()).isEqualTo("image/jpeg");
        assertThat(http.sent.get(1).uri().getScheme()).isEqualTo("http");
        // Missing img: explicit null cover, no outbound, no reference warning.
        var missing = new FakeTransport();
        missing.replies.add(json(envelope(data(ISBN10, "").replace(",\"img\":\"https://static1.showapi.com/app2/isbn/imgs/book.jpg\"", ""))));
        var r2 = client(missing, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(r2.cover()).isNull();
        assertThat(missing.sent).hasSize(1);
    }

    @Test
    void acS3NumericDirectoryCoverFromLiveUpstreamIsFetchedAndValidated() throws Exception {
        String path = "/app2/isbn/532/88a6eae4990443aca573b0395844a8a3.jpg";
        for (String scheme : new String[]{"http", "https"}) {
            String url = scheme + "://static1.showapi.com" + path;
            var transport = new FakeTransport();
            transport.replies.add(json(envelope(data(ISBN10, "\"img\":\"" + url + "\""))));
            byte[] jpeg = BookCoverApiTest.image("jpeg", 5, 7);
            transport.replies.add(new Reply(200, "image/jpeg", null, jpeg));

            var response = client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);

            assertThat(response.cover()).as(url).isNotNull();
            assertThat(response.cover().base64()).isEqualTo(Base64.getEncoder().encodeToString(jpeg));
            assertThat(response.cover().mediaType()).isEqualTo("image/jpeg");
            assertThat(response.warnings()).isEmpty();
            assertThat(transport.sent).hasSize(2);
            assertThat(transport.sent.get(1).uri()).isEqualTo(URI.create(url));
            assertThat(transport.sent.get(1).body()).isNull();
            assertThat(transport.sent.get(1).uri().toString()).doesNotContain(KEY);
        }
    }

    @Test
    void t2NumericDirectoryCoverStillRejectsUnsafeReferences() {
        String hash = "88a6eae4990443aca573b0395844a8a3";
        for (String url : new String[]{
                "https://static1.showapi.com.evil/app2/isbn/532/" + hash + ".jpg",
                "https://static1.showapi.com/app2/isbn/../" + hash + ".jpg",
                "https://static1.showapi.com/app2/isbn/%35%33%32/" + hash + ".jpg",
                "https://static1.showapi.com/app2/isbn/532/" + hash + ".jpg?token=x",
                "https://static1.showapi.com/app2/isbn/532/" + hash + ".jpg#fragment",
                "https://static1.showapi.com/app2/isbn/arbitrary/" + hash + ".jpg",
                "https://static1.showapi.com/app2/isbn/532/nested/" + hash + ".jpg",
                "https://static1.showapi.com/app2/isbn/532/" + hash + ".gif"}) {
            var warnings = new ArrayList<ShowApiLookupResponse.Warning>();
            assertThat(ShowApiClient.coverUri(tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(url), warnings))
                    .as(url).isNull();
            assertThat(warnings).extracting(ShowApiLookupResponse.Warning::code).contains("COVER_REFERENCE_REJECTED");
        }
    }

    @Test
    void acS3CoverFailuresKeepMetadataAndNeverFollowRedirects() throws Exception {
        var transport = new FakeTransport();
        transport.replies.add(json(envelope(data(ISBN10, ""))));
        transport.replies.add(new Reply(302, "text/html", null, new byte[0]));
        var response = client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(response.found()).isTrue();
        assertThat(response.cover()).isNull();
        assertThat(response.warnings()).extracting(ShowApiLookupResponse.Warning::code).contains("COVER_UNAVAILABLE");
        assertThat(transport.sent).hasSize(2);

        var bad = new FakeTransport();
        bad.replies.add(json(envelope(data(ISBN10, ""))));
        bad.replies.add(new Reply(200, "image/png", null, new byte[]{1, 2, 3}));
        var r2 = client(bad, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(r2.found()).isTrue();
        assertThat(r2.cover()).isNull();
        assertThat(r2.warnings()).isNotEmpty();

        var limited = new FakeTransport();
        limited.replies.add(json(envelope(data(ISBN10, ""))));
        limited.replies.add(new Reply(429, "text/plain", "30", new byte[0]));
        var r3 = client(limited, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
        assertThat(r3.found()).isTrue();
        assertThat(r3.cover()).isNull();
        assertThat(r3.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void t1RateLimitCooldownThrottleAndSinglePermit() throws Exception {
        var transport = new FakeTransport();
        transport.replies.add(new Reply(429, "text/plain", "120", new byte[0]));
        var time = new AtomicLong(1_000_000_000L);
        var c = client(transport, time, ENABLED, NO_PROXY);
        assertThatThrownBy(() -> c.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_RATE_LIMITED);
            assertThat(ex.getDetails().get("retryAfterSeconds")).isEqualTo(120);
        });
        int before = transport.sent.size();
        assertThatThrownBy(() -> c.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getDetails().get("reason")).isEqualTo("COOLDOWN"));
        assertThat(transport.sent).hasSize(before);

        var invalid = new FakeTransport();
        invalid.replies.add(new Reply(429, "text/plain", "not-a-number", new byte[0]));
        assertThatThrownBy(() -> client(invalid, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getDetails().get("retryAfterSeconds")).isEqualTo(60));

        // metadata at most once per second: second query without elapsed time is throttled, no outbound.
        var throttle = new FakeTransport();
        throttle.replies.add(json(envelope(data(ISBN10, ""))));
        throttle.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        throttle.replies.add(json(envelope(data(ISBN10, ""))));
        throttle.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        var fixed = new AtomicLong(5_000_000_000L);
        var tc = client(throttle, fixed, ENABLED, NO_PROXY);
        tc.lookup(ISBN10);
        assertThatThrownBy(() -> tc.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_RATE_LIMITED);
            assertThat(ex.getDetails().get("reason")).isEqualTo("THROTTLED");
        });
        assertThat(throttle.sent).hasSize(2);
        fixed.addAndGet(1_000_000_000L);
        assertThat(tc.lookup(ISBN10).found()).isTrue();

        // Single permit, no queue: a busy client rejects immediately with 429.
        var busy = new FakeTransport() {
            final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            @Override public Reply exchange(URI uri, String method, byte[] body, Route route, int maxBytes, Duration timeout)
                    throws TransportFailure {
                if (calls == 0) {
                    started.countDown();
                    try {
                        // Hold the permit by blocking inside the first exchange until released.
                        latch.await();
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                return super.exchange(uri, method, body, route, maxBytes, timeout);
            }
        };
        busy.replies.add(json(envelope(data(ISBN10, ""))));
        busy.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        var bc = client(busy, new AtomicLong(9_000_000_000L), ENABLED, NO_PROXY);
        var worker = new Thread(() -> bc.lookup(ISBN10));
        worker.start();
        assertThat(busy.started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> bc.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_RATE_LIMITED);
            assertThat(ex.getDetails().get("reason")).isEqualTo("BUSY");
        });
        busy.latch.countDown();
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    void t1DeadlinesAndNoRetryAfterRequestSent() throws Exception {
        // Total deadline exhausted by the transport clock: 502 TIMEOUT, permit released afterwards.
        var time = new AtomicLong(1);
        var slow = new FakeTransport() {
            @Override public Reply exchange(URI uri, String method, byte[] body, Route route, int maxBytes, Duration timeout) {
                time.addAndGet(Duration.ofSeconds(15).toNanos());
                return new Reply(200, "application/json", null, envelope(data(ISBN10, "")).getBytes(StandardCharsets.UTF_8));
            }
        };
        var c = client(slow, time, ENABLED, NO_PROXY);
        assertThatThrownBy(() -> c.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_UNAVAILABLE);
            assertThat(ex.getDetails().get("reason")).isEqualTo("TIMEOUT");
        });

        // RESET/TIMEOUT after the request was sent must not be retried, even with a proxy configured.
        for (var failure : new TransportFailure[]{
                new TransportFailure(FailureReason.RESET, false, true),
                new TransportFailure(FailureReason.TIMEOUT, true, true),
                new TransportFailure(FailureReason.TIMEOUT, false, true),
                new TransportFailure(FailureReason.ADDRESS_REJECTED, false, false)}) {
            var transport = new FakeTransport();
            transport.failures.add(failure);
            var cc = client(transport, new AtomicLong(1), ENABLED, PROXY);
            assertThatThrownBy(() -> cc.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_UNAVAILABLE));
            assertThat(transport.sent).hasSize(1);
        }
        // Proven unsent DNS/connect failures may use the admin proxy exactly once.
        for (var reason : new FailureReason[]{FailureReason.DNS, FailureReason.CONNECT, FailureReason.NO_ROUTE}) {
            var transport = new FakeTransport();
            transport.failures.add(new TransportFailure(reason, false, false));
            transport.replies.add(null);
            transport.replies.add(json(envelope(data(ISBN10, ""))));
            transport.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
            var response = client(transport, new AtomicLong(1), ENABLED, PROXY).lookup(ISBN10);
            assertThat(response.found()).isTrue();
            assertThat(transport.sent.get(0).route().kind()).isEqualTo(RouteKind.DIRECT);
            assertThat(transport.sent.get(1).route().kind()).isEqualTo(RouteKind.SOCKS5);
            assertThat(transport.sent.get(1).route().host()).isEqualTo("127.0.0.1");
        }
        // Without a proxy a DNS failure surfaces immediately as 502.
        var noProxy = new FakeTransport();
        noProxy.failures.add(new TransportFailure(FailureReason.DNS, false, false));
        assertThatThrownBy(() -> client(noProxy, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_UNAVAILABLE));
        assertThat(noProxy.sent).hasSize(1);
    }

    @Test
    void t2UpstreamAuthFailureIs502NotLocalLogoutAndConfigIsRequired() throws Exception {
        for (int status : new int[]{401, 403}) {
            var transport = new FakeTransport();
            transport.replies.add(new Reply(status, "application/json", null, new byte[0]));
            assertThatThrownBy(() -> client(transport, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_AUTH_FAILED);
                        assertThat(ex.getCode().getStatus().value()).isEqualTo(502);
                    });
        }
        var disabled = new FakeTransport();
        ShowApiSettingsProvider off = () -> new ShowApiSettings(false, null, 0, null);
        assertThatThrownBy(() -> client(disabled, new AtomicLong(1), off, NO_PROXY).lookup(ISBN10))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_NOT_CONFIGURED));
        assertThat(disabled.sent).isEmpty();
        var other = new FakeTransport();
        other.replies.add(new Reply(500, "text/plain", null, "internal detail".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> client(other, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain("internal detail");
                });
    }

    @Test
    void t2KeyNeverAppearsInResponsesErrorsOrLogs() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShowApiClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var ok = new FakeTransport();
            ok.replies.add(json(envelope(data(ISBN10, ""))));
            ok.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
            var response = client(ok, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10);
            assertThat(response.toString()).doesNotContain(KEY);
            var auth = new FakeTransport();
            auth.replies.add(new Reply(403, "application/json", null, new byte[0]));
            assertThatThrownBy(() -> client(auth, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getMessage()).doesNotContain(KEY);
                        assertThat(ex.getDetails().toString()).doesNotContain(KEY);
                    });
            var down = new FakeTransport();
            down.failures.add(new TransportFailure(FailureReason.NETWORK, false, true));
            assertThatThrownBy(() -> client(down, new AtomicLong(1), ENABLED, NO_PROXY).lookup(ISBN10))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getDetails().toString()).doesNotContain(KEY));
            assertThat(appender.list).isNotEmpty();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(KEY).doesNotContain("appKey=");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void t1CloseReleasesResourcesAndRejectsFurtherQueries() throws Exception {
        var closed = new boolean[]{false};
        var transport = new FakeTransport() {
            @Override public void close() { closed[0] = true; }
        };
        transport.replies.add(json(envelope(data(ISBN10, ""))));
        transport.replies.add(new Reply(404, "image/jpeg", null, new byte[0]));
        var c = client(transport, new AtomicLong(1), ENABLED, NO_PROXY);
        assertThat(c.lookup(ISBN10).found()).isTrue();
        c.close();
        assertThat(closed[0]).isTrue();
        assertThatThrownBy(() -> c.lookup(ISBN10)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SHOWAPI_UNAVAILABLE));
        assertThat(transport.sent).hasSize(2);
    }

    @Test
    void acS2PreviewNeverTouchesPersistenceAndServiceValidatesIsbn() {
        var service = new ShowApiClient(new FakeTransport(), () -> 1L, nanos -> { }, new CoverImageValidator(),
                NO_PROXY, ENABLED);
        var lookup = new com.example.library.catalog.ShowApiService(service);
        for (String bad : new String[]{null, "123", "9787115428029", "https://x"}) {
            assertThatThrownBy(() -> lookup.lookup(bad)).isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.getFieldErrors()).extracting(com.example.library.common.error.ErrorResponse.FieldErrorItem::field)
                        .contains("isbn");
            });
        }
    }
}