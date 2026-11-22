package com.example.library.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenLibraryClientTest {
    private static final String ISBN = "080442957X";
    private static OpenLibraryClient.Reply json(int status, String body) {
        return new OpenLibraryClient.Reply(status, "application/json", null, body.getBytes(StandardCharsets.UTF_8));
    }
    private static String book(String value) { return "{\"ISBN:" + ISBN + "\":" + value + "}"; }

    private static class Harness {
        final AtomicLong time = new AtomicLong(1);
        final ArrayDeque<OpenLibraryClient.Reply> replies = new ArrayDeque<>();
        final List<URI> requests = new ArrayList<>();
        final List<Duration> deadlines = new ArrayList<>();
        final OpenLibraryClient client = new OpenLibraryClient((uri, max, timeout) -> {
            requests.add(uri); deadlines.add(timeout);
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getUserInfo()).isNull();
            assertThat(max).isIn(256 * 1024, 2097152);
            return replies.removeFirst();
        }, time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
    }

    @Test
    void acOl02ParsesFieldsAndOnlyConstructsFixedIsbnAndCoverIdAddresses() throws Exception {
        var h = new Harness();
        h.replies.add(json(200, book("{\"title\":\"  标题  \",\"authors\":[{\"name\":\"A\"},{\"name\":\" A \"},{\"name\":\"B\"}],"
                + "\"publishers\":[{\"name\":\"社\"}],\"publish_date\":\"October 1998\",\"description\":\" 简介 \","
                + "\"cover\":{\"medium\":\"https://covers.openlibrary.org/b/id/123-M.jpg\"}}")));
        byte[] png = BookCoverApiTest.image("png", 4, 6);
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, png));
        var result = h.client.lookup(ISBN);
        assertThat(result.found()).isTrue(); assertThat(result.isbn()).isEqualTo(ISBN);
        assertThat(result.sourceUrl()).isEqualTo("https://openlibrary.org/isbn/" + ISBN);
        assertThat(result.fields().title()).isEqualTo("标题"); assertThat(result.fields().author()).isEqualTo("A、B");
        assertThat(result.fields().publisher()).isEqualTo("社"); assertThat(result.fields().publishYear()).isEqualTo(1998);
        assertThat(result.fields().description()).isEqualTo("简介");
        assertThat(result.cover().byteLength()).isEqualTo(png.length); assertThat(result.cover().width()).isEqualTo(4);
        assertThat(java.util.Base64.getDecoder().decode(result.cover().base64())).isEqualTo(png);
        assertThat(result.warnings()).isEmpty(); assertThat(result.retryAfterSeconds()).isNull();
        assertThat(h.requests).containsExactly(
                URI.create("https://openlibrary.org/api/books?bibkeys=ISBN:" + ISBN + "&jscmd=data&format=json"),
                URI.create("https://covers.openlibrary.org/b/id/123-M.jpg?default=false"));
        assertThat(h.time.get()).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        assertThat(h.deadlines).allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(Duration.ofSeconds(6)));
    }

    @Test
    void acOl04MissingMatchingKeyAndMetadata404AreNotFoundWithoutCoverAttempt() {
        for (var reply : List.of(json(404, "not found"), json(200, "{}"), json(200, "{\"ISBN:other\":{\"title\":\"wrong\"}}"))) {
            var h = new Harness(); h.replies.add(reply);
            var result = h.client.lookup(ISBN);
            assertThat(result.found()).isFalse(); assertThat(result.cover()).isNull();
            assertThat(result.fields().title()).isNull(); assertThat(result.fields().author()).isNull();
            assertThat(result.fields().publisher()).isNull(); assertThat(result.fields().description()).isNull();
            assertThat(result.fields().publishYear()).isNull(); assertThat(h.requests).hasSize(1);
        }
    }

    @Test
    void acOl04MissingAuthorsAndNotesNeverBecomeInventedMetadata() {
        var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\",\"notes\":\"不是简介\"}")));
        h.replies.add(json(404, ""));
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().author()).isNull(); assertThat(result.fields().description()).isNull();
        assertThat(result.cover()).isNull();
        assertThat(h.requests.getLast().toString()).isEqualTo("https://covers.openlibrary.org/b/isbn/" + ISBN + "-M.jpg?default=false");
    }

    @Test
    void acOl04BadTypesLengthsAndAmbiguousDatesYieldNullFieldsAndWarnings() {
        var h = new Harness();
        h.replies.add(json(200, book("{\"title\":\"" + "x".repeat(201) + "\",\"authors\":\"bad\","
                + "\"publishers\":[{\"name\":\"" + "x".repeat(101) + "\"}],\"publish_date\":\"1998 / 2000\","
                + "\"description\":true,\"cover\":{\"medium\":\"http://127.0.0.1/a\"}}")));
        var result = h.client.lookup(ISBN);
        assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isNull(); assertThat(result.fields().author()).isNull();
        assertThat(result.fields().publisher()).isNull(); assertThat(result.fields().description()).isNull(); assertThat(result.fields().publishYear()).isNull();
        assertThat(result.warnings()).hasSize(6); assertThat(h.requests).hasSize(1);
        assertThat(result.warnings()).extracting(w -> w.field()).contains("title", "author", "publisher", "description", "publishYear", "cover");
    }

    @Test
    void dod02RejectsNonPermittedReferencesWithoutFallbackRequest() {
        for (String reference : new String[]{"http://covers.openlibrary.org/b/id/1-M.jpg", "https://evil.example/b/id/1-M.jpg",
                "https://covers.openlibrary.org.evil.example/b/id/1-M.jpg", "https://user@covers.openlibrary.org/b/id/1-M.jpg",
                "https://covers.openlibrary.org:8443/b/id/1-M.jpg", "https://covers.openlibrary.org/b/id/0-M.jpg",
                "https://covers.openlibrary.org/b/id/1-M.jpg#fragment", "https://covers.openlibrary.org/b/id/999999999999999999999-M.jpg"}) {
            var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\",\"cover\":{\"medium\":\"" + reference + "\"}}")));
            var result = h.client.lookup(ISBN);
            assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).isNotEmpty(); assertThat(h.requests).hasSize(1);
        }
    }

    @Test
    void acOl04CoverErrorsDoNotDiscardSuccessfulBookFieldsAndNeverRetry() {
        for (var cover : List.of(json(302, "redirect"), json(500, "bad"), new OpenLibraryClient.Reply(200, "image/png", null, "<html>".getBytes()),
                new OpenLibraryClient.Reply(200, "image/gif", null, new byte[]{1}), new OpenLibraryClient.Reply(200, "image/png", null, new byte[2097153]))) {
            var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\"}"))); h.replies.add(cover);
            var result = h.client.lookup(ISBN);
            assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).isNotEmpty(); assertThat(h.requests).hasSize(2);
        }
    }

    @Test
    void acOl04MetadataMalformedRedirectTooLargeAndFailuresBecome502() {
        for (var meta : List.of(json(200, "invalid JSON"), json(200, "[]"), json(200, book("\"bad\"")), json(302, ""), json(503, ""),
                new OpenLibraryClient.Reply(200, "application/json", null, new byte[256 * 1024 + 1]))) {
            var h = new Harness(); h.replies.add(meta);
            assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                    e -> { assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_UNAVAILABLE); assertThat(e.getFieldErrors()).isEmpty(); });
            assertThat(h.requests).hasSize(1);
        }
        for (IOException failure : List.of(new HttpTimeoutException("fixture"), new IOException("fixture"))) {
            var client = new OpenLibraryClient((uri, max, timeout) -> { throw failure; }, System::nanoTime,
                    nanos -> TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator());
            assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getDetails().get("reason")).isEqualTo(failure instanceof HttpTimeoutException ? "TIMEOUT" : "NETWORK"));
        }
    }

    @Test
    void dod02NegativeMonotonicEpochDoesNotInventCooldown() {
        var h = new Harness(); h.time.set(-TimeUnit.SECONDS.toNanos(1000));
        h.replies.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).found()).isFalse();
        assertThat(h.requests).hasSize(1);
    }

    @Test
    void acOl02UniqueYearIsParsedWithoutAnUnspecifiedDateStringLimit() {
        var h = new Harness();
        h.replies.add(json(200, book("{\"publish_date\":\"1998 " + "a".repeat(150) + "\"}")));
        h.replies.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).fields().publishYear()).isEqualTo(1998);
    }

    @Test
    void dod02RollingWindowCountsMetadataAndCoverAsSeparateAttempts() {
        var h = new Harness();
        for (int i = 0; i < 59; i++) { h.replies.add(json(404, "")); h.client.lookup(ISBN); }
        h.replies.add(json(200, book("{\"title\":\"book\"}")));
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.retryAfterSeconds()).isBetween(1, 300);
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_RATE_LIMITED");
        assertThat(h.requests).hasSize(60);
    }

    @Test
    void dod02Metadata429EnforcesSanitizedCooldownAndNoAutomaticRetry() {
        for (String raw : new String[]{null, "bad", "0", "301", "Wed, 21 Oct 2026", " 5 "}) {
            var h = new Harness(); h.replies.add(new OpenLibraryClient.Reply(429, "application/json", raw, new byte[0]));
            int expected = " 5 ".equals(raw) ? 5 : 60;
            assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                    e -> { assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED); assertThat(e.getDetails().get("retryAfterSeconds")).isEqualTo(expected); });
            assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class);
            assertThat(h.requests).hasSize(1);
            h.time.addAndGet(TimeUnit.SECONDS.toNanos(expected)); h.replies.add(json(404, ""));
            assertThat(h.client.lookup(ISBN).found()).isFalse(); assertThat(h.requests).hasSize(2);
        }
    }

    @Test
    void acOl04Cover429ReturnsPartialBookAndRetrySeconds() {
        var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\"}")));
        h.replies.add(new OpenLibraryClient.Reply(429, "image/jpeg", "7", new byte[0]));
        var result = h.client.lookup(ISBN);
        assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isEqualTo("book");
        assertThat(result.retryAfterSeconds()).isEqualTo(7); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_RATE_LIMITED");
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class);
        assertThat(h.requests).hasSize(2);
    }

    @Test
    void dod02RollingWindowLimitsAllRealExchangesAndThenRecovers() {
        var h = new Harness();
        for (int i = 0; i < 60; i++) { h.replies.add(json(404, "")); assertThat(h.client.lookup(ISBN).found()).isFalse(); }
        assertThat(h.requests).hasSize(60);
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> { assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED); assertThat((Integer)e.getDetails().get("retryAfterSeconds")).isBetween(1, 300); });
        h.time.addAndGet(TimeUnit.MINUTES.toNanos(5)); h.replies.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).found()).isFalse(); assertThat(h.requests).hasSize(61);
    }

    @Test
    void dod02SingleQueryPermitRejectsConcurrencyAndReleasesOnFailure() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var client = new OpenLibraryClient((uri, max, timeout) -> { entered.countDown(); finish.await(3, TimeUnit.SECONDS); throw new IOException("fixture"); },
                System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOf(ApiException.class));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getDetails().get("reason")).isEqualTo("BUSY"));
            finish.countDown(); first.get(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getDetails().get("reason")).isEqualTo("NETWORK"));
        } finally { finish.countDown(); }
    }

    private static class ProxyHarness {
        final AtomicLong time = new AtomicLong(1);
        final java.util.concurrent.atomic.AtomicReference<com.example.library.common.proxy.ProxySnapshot> settings =
                new java.util.concurrent.atomic.AtomicReference<>(new com.example.library.common.proxy.ProxySnapshot(true, "localhost", 9999, 1, null));
        final ArrayDeque<Object> results = new ArrayDeque<>();
        final List<OpenLibraryClient.Route> routes = new ArrayList<>();
        final List<URI> requests = new ArrayList<>();
        final List<Duration> deadlines = new ArrayList<>();
        Runnable onAttempt = () -> { };
        final OpenLibraryClient client = new OpenLibraryClient((OpenLibraryClient.RoutedTransport) (uri, route, max, timeout) -> {
            routes.add(route); requests.add(uri); deadlines.add(timeout); onAttempt.run();
            Object next = results.removeFirst();
            if (next instanceof IOException failure) { throw failure; }
            return (OpenLibraryClient.Reply) next;
        }, time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator(), settings::get);
        void fail(OpenLibraryClient.FailureReason reason, boolean headers) { results.add(new OpenLibraryClient.TransportFailure(reason, headers)); }
    }

    @Test
    void acProxy03DirectSuccessNeverTouchesEnabledProxy() {
        var h = new ProxyHarness(); h.results.add(json(200, book("{\"title\":\"book\"}"))); h.results.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).fields().title()).isEqualTo("book");
        assertThat(h.routes).extracting(OpenLibraryClient.Route::kind).containsExactly(OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.DIRECT);
    }

    @Test
    void acProxy04MetadataAndCoverEachStartDirectThenOneSocksAttempt() throws Exception {
        var h = new ProxyHarness(); h.fail(OpenLibraryClient.FailureReason.DNS, false);
        h.results.add(json(200, book("{\"title\":\"book\"}"))); h.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        h.results.add(new OpenLibraryClient.Reply(200, "image/png", null, BookCoverApiTest.image("png", 4, 6)));
        assertThat(h.client.lookup(ISBN).cover().width()).isEqualTo(4);
        assertThat(h.routes).extracting(OpenLibraryClient.Route::kind).containsExactly(OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.SOCKS5,
                OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.SOCKS5);
        assertThat(h.requests.get(0)).isEqualTo(h.requests.get(1)); assertThat(h.requests.get(2)).isEqualTo(h.requests.get(3));
        assertThat(h.time.get()).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(3));
    }

    @Test
    void acProxy05DisabledBothFailuresAndCoverPartialHaveSanitizedDiagnostics() {
        var disabled = new ProxyHarness(); disabled.settings.set(new com.example.library.common.proxy.ProxySnapshot(false, null, null, 0, null));
        disabled.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        assertThatThrownBy(() -> disabled.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("stage", "METADATA").containsEntry("lastRoute", "DIRECT").containsEntry("proxyOutcome", "DISABLED"));
        assertThat(disabled.routes).hasSize(1);
        var both = new ProxyHarness(); both.fail(OpenLibraryClient.FailureReason.RESET, false); both.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        assertThatThrownBy(() -> both.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("lastRoute", "SOCKS5").containsEntry("proxyOutcome", "FAILED")
                        .containsEntry("directReason", "RESET").containsEntry("proxyReason", "CONNECT"));
        assertThat(both.routes).hasSize(2);
        var partial = new ProxyHarness(); partial.results.add(json(200, book("{\"title\":\"book\"}")));
        partial.fail(OpenLibraryClient.FailureReason.TIMEOUT, false); partial.fail(OpenLibraryClient.FailureReason.DNS, false);
        assertThat(partial.client.lookup(ISBN).fields().title()).isEqualTo("book"); assertThat(partial.routes).hasSize(3);
    }

    @Test
    void acProxy06NonNetworkOrAnyResponseHeadersNeverFallback() {
        for (var reason : OpenLibraryClient.FailureReason.values()) {
            for (boolean headers : new boolean[]{false, true}) {
                var h = new ProxyHarness(); var failure = new OpenLibraryClient.TransportFailure(reason, headers); h.results.add(failure);
                if (failure.fallbackAllowed()) { continue; }
                assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class);
                assertThat(h.routes).hasSize(1);
            }
        }
        for (int status : new int[]{200, 301, 302, 404, 429, 500, 503}) {
            var h = new ProxyHarness(); h.results.add(json(status, "invalid"));
            if (status == 404) { assertThat(h.client.lookup(ISBN).found()).isFalse(); }
            else { assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class); }
            assertThat(h.routes).hasSize(1);
        }
        var unknown = new ProxyHarness(); unknown.results.add(new IOException("secret proxy address"));
        assertThatThrownBy(() -> unknown.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "NETWORK").containsEntry("proxyOutcome", "NOT_ATTEMPTED"));
        assertThat(unknown.routes).hasSize(1);
    }

    @Test
    void acProxy06ImmutableQuerySnapshotAndProxy429CooldownSurviveConfigChange() {
        var h = new ProxyHarness(); h.onAttempt = () -> h.settings.set(new com.example.library.common.proxy.ProxySnapshot(true, "next.example", 8888, 2, null));
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(new OpenLibraryClient.Reply(429, "application/json", "5", new byte[0]));
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED));
        assertThat(h.routes.get(1).host()).isEqualTo("localhost"); assertThat(h.routes.get(1).port()).isEqualTo(9999);
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class); assertThat(h.routes).hasSize(2);
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(5)); h.fail(OpenLibraryClient.FailureReason.RESET, false); h.results.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).found()).isFalse(); assertThat(h.routes.getLast().host()).isEqualTo("next.example");
    }

    @Test
    void acProxy06QueryReadsSnapshotOnceEvenWhenConfigurationChangesBetweenStages() throws Exception {
        var settings = new java.util.concurrent.atomic.AtomicReference<>(new com.example.library.common.proxy.ProxySnapshot(true, "localhost", 9999, 1, null));
        var reads = new AtomicLong(); var calls = new AtomicLong(); var hosts = new ArrayList<String>(); var time = new AtomicLong(1);
        byte[] image = BookCoverApiTest.image("png", 4, 6);
        var client = new OpenLibraryClient((OpenLibraryClient.RoutedTransport) (uri, route, max, timeout) -> {
            long call = calls.incrementAndGet(); settings.set(new com.example.library.common.proxy.ProxySnapshot(false, null, null, 2, null));
            if (route.kind() == OpenLibraryClient.RouteKind.DIRECT) { throw new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.CONNECT, false); }
            hosts.add(route.host());
            return call == 2 ? json(200, book("{\"title\":\"book\"}")) : new OpenLibraryClient.Reply(200, "image/png", null, image);
        }, time::get, time::addAndGet, new CoverImageValidator(), () -> { reads.incrementAndGet(); return settings.get(); });
        assertThat(client.lookup(ISBN).cover().width()).isEqualTo(4); assertThat(reads.get()).isEqualTo(1);
        assertThat(hosts).containsExactly("localhost", "localhost");
    }

    @Test
    void acProxy06ProxyAttemptsCountTowardSharedRollingLimitAndBudget() {
        var h = new ProxyHarness();
        for (int i = 0; i < 30; i++) {
            h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(json(404, "")); assertThat(h.client.lookup(ISBN).found()).isFalse();
        }
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED)); assertThat(h.routes).hasSize(60);
        var budget = new ProxyHarness(); budget.onAttempt = () -> budget.time.addAndGet(TimeUnit.SECONDS.toNanos(6));
        budget.fail(OpenLibraryClient.FailureReason.CONNECT, false); budget.results.add(json(200, book("{\"title\":\"book\"}")));
        budget.fail(OpenLibraryClient.FailureReason.TIMEOUT, false);
        assertThat(budget.client.lookup(ISBN).fields().title()).isEqualTo("book"); assertThat(budget.routes).hasSize(3);
        assertThat(budget.deadlines).containsExactly(Duration.ofSeconds(6), Duration.ofSeconds(6), Duration.ofSeconds(2));
    }

    @Test
    void acProxy06SubMillisecondBudgetNeverSendsOrCountsAnAttempt() {
        var time = new AtomicLong(1); var calls = new AtomicLong();
        var client = new OpenLibraryClient((uri, max, timeout) -> { calls.incrementAndGet(); return json(404, ""); },
                time::get, nanos -> time.addAndGet(TimeUnit.SECONDS.toNanos(14) - 500_000), new CoverImageValidator());
        assertThat(client.lookup(ISBN).found()).isFalse();
        assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "TIMEOUT").containsEntry("stage", "METADATA"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat((java.util.ArrayDeque<?>) org.springframework.test.util.ReflectionTestUtils.getField(client, "exchanges")).hasSize(1);
    }

    @Test
    void acProxy06PendingTransportCleanupBlocksFallbackAndKeepsSinglePermit() {
        var calls = new AtomicLong(); var cleanup = new CompletableFuture<Void>();
        var transport = new OpenLibraryClient.RoutedTransport() {
            public OpenLibraryClient.Reply get(URI uri, OpenLibraryClient.Route route, int max, Duration timeout) throws IOException {
                calls.incrementAndGet(); throw new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.RESET, false);
            }
            public java.util.concurrent.CompletionStage<Void> quiescence() {
                return calls.get() == 0 ? CompletableFuture.completedFuture(null) : cleanup;
            }
        };
        var time = new AtomicLong(1);
        var client = new OpenLibraryClient(transport, time::get, time::addAndGet, new CoverImageValidator(),
                () -> new com.example.library.common.proxy.ProxySnapshot(true, "localhost", 9999, 0, null));
        assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOf(ApiException.class); assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "BUSY")); assertThat(calls.get()).isEqualTo(1);
        cleanup.complete(null);
        assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOf(ApiException.class); assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void acProxy06ClosePreventsNewRequestsAndInterruptDoesNotFallback() {
        var h = new ProxyHarness(); h.client.close();
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class); assertThat(h.routes).isEmpty();
        var cancelled = new ProxyHarness(); cancelled.onAttempt = () -> Thread.currentThread().interrupt();
        cancelled.fail(OpenLibraryClient.FailureReason.RESET, false);
        try { assertThatThrownBy(() -> cancelled.client.lookup(ISBN)).isInstanceOf(ApiException.class); assertThat(cancelled.routes).hasSize(1); }
        finally { Thread.interrupted(); }
    }

    @Test
    void acProxy06TlsFailureIsNotMisclassifiedAsNetwork() {
        var client = new OpenLibraryClient((uri, max, timeout) -> { throw new javax.net.ssl.SSLHandshakeException("fixture"); },
                System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator());
        assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails().get("reason")).isEqualTo("TLS"));
    }

    @Test
    void acProxy05CoverBudgetExhaustionKeepsValidMetadata() {
        var time = new AtomicLong(1); var calls = new AtomicLong();
        var client = new OpenLibraryClient((uri, max, timeout) -> {
            if (calls.incrementAndGet() == 1) { return json(200, book("{\"title\":\"book\"}")); }
            time.addAndGet(TimeUnit.SECONDS.toNanos(14)); throw new HttpTimeoutException("fixture");
        }, time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
        var result = client.lookup(ISBN);
        assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isEqualTo("book");
        assertThat(result.cover()).isNull(); assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void dod02TotalQueryBudgetBoundsSecondExchangeAndTimesOut() {
        var time = new AtomicLong(1); var calls = new ArrayList<Duration>();
        var client = new OpenLibraryClient((uri, max, timeout) -> {
            calls.add(timeout); time.addAndGet(TimeUnit.SECONDS.toNanos(6));
            return calls.size() == 1 ? json(200, book("{\"title\":\"book\"}")) : json(404, "");
        }, time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
        assertThat(client.lookup(ISBN).fields().title()).isEqualTo("book");
        assertThat(calls).containsExactly(Duration.ofSeconds(6), Duration.ofSeconds(6));
        time.set(1); calls.clear();
        var over = new OpenLibraryClient((uri, max, timeout) -> { time.addAndGet(TimeUnit.SECONDS.toNanos(15)); return json(404, ""); },
                time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
        assertThatThrownBy(() -> over.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails().get("reason")).isEqualTo("TIMEOUT"));
    }

    @Test
    void dod02TransportTimeoutIncludesBodyAndCancelsHttpAndSubscriber() throws Exception {
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.stallBody = true;
            assertThatThrownBy(() -> transport.get(ReactorNettyTransportTest.META, OpenLibraryClient.Route.direct(), 256 * 1024, Duration.ofMillis(500)))
                    .isInstanceOfSatisfying(OpenLibraryClient.TransportFailure.class,
                            e -> { assertThat(e.reason).isEqualTo(OpenLibraryClient.FailureReason.TIMEOUT); assertThat(e.headersReceived).isTrue(); });
            transport.quiescence().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(fixture.httpRequests).hasSize(1);
            assertThat(fixture.httpRequests.getFirst()).startsWith("GET ").contains("LibraryCatalog")
                    .doesNotContain("Cookie:", "X-XSRF-TOKEN:", "Content-Length:");
        }
    }

    @Test
    void dod02ProductionTransportHasTlsDefaultConnectTimeoutAndNoRedirects() {
        try (var transport = new ReactorNettyTransport()) {
            var http = (reactor.netty.http.client.HttpClient) org.springframework.test.util.ReflectionTestUtils.getField(transport, "base");
            var config = http.configuration();
            assertThat(config.options().get(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS)).isEqualTo(3000);
            assertThat(config.followRedirectPredicate()).isNull(); assertThat(config.isRetryDisabled()).isTrue();
            assertThat(config.isAcceptGzip()).isFalse(); assertThat(config.isPreferNative()).isFalse();
            assertThat(config.headers().contains("Cookie")).isFalse(); assertThat(config.loggingHandler()).isNull();
            assertThat(config.sslProvider().getSslContext()).isInstanceOf(io.netty.handler.ssl.JdkSslContext.class);
            var handler = config.sslProvider().getSslContext().newHandler(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, "openlibrary.org", 443);
            config.sslProvider().configure(handler);
            assertThat(handler.engine().getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
            for (String url : List.of("http://openlibrary.org/api/books", "https://127.0.0.1/", "https://openlibrary.org:8443/", "https://u@openlibrary.org/")) {
                assertThatThrownBy(() -> transport.get(URI.create(url), 10, Duration.ofSeconds(1))).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void dod02BoundedSubscriberCancelsOverflowAndPreservesExactBytes() {
        var bounded = new ReactorNettyTransport.BoundedBody(4);
        var first = io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{1, 2});
        var excess = io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{3, 4, 5});
        var last = io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{3, 4});
        try {
            bounded.accept(first);
            assertThatThrownBy(() -> bounded.accept(excess)).isInstanceOf(ReactorNettyTransport.BodyOverflow.class);
            assertThat(bounded.bytes()).containsExactly(1, 2);
            bounded.accept(last); assertThat(bounded.bytes()).containsExactly(1, 2, 3, 4);
            assertThat(first.refCnt()).isEqualTo(1); assertThat(excess.refCnt()).isEqualTo(1); assertThat(last.refCnt()).isEqualTo(1);
            assertThat(first.readerIndex()).isZero();
        } finally { first.release(); excess.release(); last.release(); }
    }
}
