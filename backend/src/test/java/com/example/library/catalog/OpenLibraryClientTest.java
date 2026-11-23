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
        return new OpenLibraryClient.Reply(status, "application/json", null, null, body.getBytes(StandardCharsets.UTF_8));
    }
    /**
     * OL-OPT-API fixture migration: book(...) now emits an Edition JSON (GET /isbn/{isbn}.json served directly with 200)
     * whose key/ISBN validate, with the given Edition fields merged in. Legacy jscmd=data shapes are no longer used.
     */
    private static String book(String value) { return book(ISBN, value); }
    private static String book(String isbn, String value) {
        String body = value.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) { throw new IllegalArgumentException("edition fields must be an object"); }
        String inner = body.substring(1, body.length() - 1).trim();
        String id = (isbn.length() == 10 ? "\"isbn_10\":[\"" : "\"isbn_13\":[\"") + isbn + "\"]";
        return "{\"key\":\"/books/OL1M\"," + id + (inner.isEmpty() ? "" : "," + inner) + "}";
    }
    /** Distinct valid ISBN-10 values so repeated lookups are not served by the OL-OPT process cache. */
    static String isbn(int n) {
        String digits = String.format("%09d", 100_000_000 + n);
        int sum = 0;
        for (int i = 0; i < 9; i++) { sum += (digits.charAt(i) - '0') * (10 - i); }
        int check = (11 - sum % 11) % 11;
        return digits + (check == 10 ? "X" : String.valueOf(check));
    }
    private static final String COVER_URL = "https://covers.openlibrary.org/b/id/9273767-M.jpg?default=false";
    private static final String ARCHIVE_URL = "https://archive.org/download/m_covers_0009/m_covers_0009_27.zip/0009273767-M.jpg";
    private static OpenLibraryClient.Reply coverMetadata() {
        return json(200, book("{\"title\":\"book\",\"covers\":[9273767]}"));
    }
    private static OpenLibraryClient.Reply redirect(int status, String location) {
        return new OpenLibraryClient.Reply(status, "text/html", null, location, new byte[0]);
    }

    @Test
    void approvedArchiveRedirectStatusesReturnFullyValidatedCover() throws Exception {
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            var h = new Harness(); h.replies.add(coverMetadata()); h.replies.add(redirect(status, ARCHIVE_URL));
            byte[] jpeg = BookCoverApiTest.image("jpeg", 4, 6);
            h.replies.add(new OpenLibraryClient.Reply(200, "image/jpeg", null, null, jpeg));
            var result = h.client.lookup(ISBN);
            assertThat(result.cover()).isNotNull();
            assertThat(java.util.Base64.getDecoder().decode(result.cover().base64())).isEqualTo(jpeg);
            assertThat(result.warnings()).isEmpty(); assertThat(h.requests).hasSize(3);
            assertThat(h.requests.getLast()).isEqualTo(URI.create(ARCHIVE_URL));
            assertThat(h.time.get()).isEqualTo(1 + TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test
    void approvedRelativeCoverLocationAllowsTwoHopsButNotAThird() throws Exception {
        var h = new Harness(); h.replies.add(coverMetadata());
        h.replies.add(redirect(301, "9273767-M.jpg")); h.replies.add(redirect(302, ARCHIVE_URL));
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
        var successful = h.client.lookup(ISBN);
        assertThat(successful.cover()).isNotNull(); assertThat(successful.cover().width()).isEqualTo(4);
        assertThat(h.requests).hasSize(4);
        assertThat(h.requests.get(2)).isEqualTo(URI.create("https://covers.openlibrary.org/b/id/9273767-M.jpg"));
        assertThat(h.time.get()).isEqualTo(1 + TimeUnit.SECONDS.toNanos(3));
        var over = new Harness(); over.replies.add(coverMetadata());
        over.replies.add(redirect(301, "9273767-M.jpg")); over.replies.add(redirect(302, ARCHIVE_URL));
        over.replies.add(redirect(307, ARCHIVE_URL));
        var result = over.client.lookup(ISBN);
        assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("COVER_REDIRECT_LIMIT");
        assertThat(over.requests).hasSize(4);
    }

    @Test
    void coverRedirectRejectsUntrustedLocationsWithoutRequestOrDisclosure() {
        for (String location : List.of("https://archive.org.evil/download/m_covers_0009/m_covers_0009_27.zip/0009273767-M.jpg",
                ARCHIVE_URL.replace("https:", "http:"), ARCHIVE_URL.replace("archive.org", "u:secret@archive.org"),
                ARCHIVE_URL.replace("archive.org", "archive.org:8443"), ARCHIVE_URL + "#secret", ARCHIVE_URL + "?token=secret",
                ARCHIVE_URL.replace("archive.org", "127.0.0.1"), ARCHIVE_URL.replace("archive.org", "[::1]"),
                ARCHIVE_URL.replace("archive.org", "192.168.1.1"), ARCHIVE_URL.replace("archive.org", "ia800001.us.archive.org"),
                "https://archive.org/download/arbitrary/book.jpg", ARCHIVE_URL.replace("0009273767-M", "0009273768-M"),
                ARCHIVE_URL.replace("_27.zip", "_28.zip"), ARCHIVE_URL.replace("0009/", "0008/"),
                ARCHIVE_URL.replace("-M.jpg", "-L.jpg"), ARCHIVE_URL.replace("download", "%64ownload"),
                ARCHIVE_URL.replace("download/", "download/../download/"), "https://archive.org/" + "x".repeat(300),
                "https://covers.openlibrary.org/b/id/9273768-M.jpg?default=false", "//archive.org.evil/secret", "\\\\archive.org\\secret", "%")) {
            var h = new Harness(); h.replies.add(coverMetadata()); h.replies.add(redirect(302, location));
            var result = h.client.lookup(ISBN);
            assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).extracting(w -> w.code()).contains("COVER_REDIRECT_REJECTED");
            assertThat(result.warnings().toString()).doesNotContain(location, "secret"); assertThat(h.requests).hasSize(2);
        }
    }

    @Test
    void missingLocationAndLoopsHaveDistinctWarningsAndNoExtraRequest() {
        for (String location : new String[]{null, "", " "}) {
            var h = new Harness(); h.replies.add(coverMetadata()); h.replies.add(redirect(302, location));
            assertThat(h.client.lookup(ISBN).warnings()).extracting(w -> w.code()).contains("COVER_REDIRECT_MISSING_LOCATION");
            assertThat(h.requests).hasSize(2);
        }
        var h = new Harness(); h.replies.add(coverMetadata()); h.replies.add(redirect(302, COVER_URL));
        assertThat(h.client.lookup(ISBN).warnings()).extracting(w -> w.code()).contains("COVER_REDIRECT_LOOP");
        assertThat(h.requests).hasSize(2);
    }

    @Test
    void metadataRedirectNeverFollowsEvenApprovedArchiveLocation() {
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            var h = new Harness(); h.replies.add(redirect(status, ARCHIVE_URL));
            assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class);
            assertThat(h.requests).hasSize(1);
        }
    }

    @Test
    void archiveRedirectRequiresKnownCoverIdNotAnUnverifiedIsbnMapping() {
        var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\"}")));
        h.replies.add(redirect(302, ARCHIVE_URL));
        assertThat(h.client.lookup(ISBN).warnings()).extracting(w -> w.code()).contains("COVER_REDIRECT_REJECTED");
        assertThat(h.requests).hasSize(2);
    }

    @Test
    void archive429KeepsMetadataAndSharedCooldownWithoutRetryOrRouteSwitch() {
        for (boolean viaSocks : new boolean[]{false, true}) {
            var h = new ProxyHarness(); h.results.add(coverMetadata());
            if (viaSocks) { h.fail(OpenLibraryClient.FailureReason.CONNECT, false); }
            h.results.add(redirect(302, ARCHIVE_URL));
            h.results.add(new OpenLibraryClient.Reply(429, "text/html", "7", null, new byte[0]));
            var result = h.client.lookup(ISBN);
            assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.retryAfterSeconds()).isEqualTo(7);
            assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_RATE_LIMITED");
            assertThat(result.warnings()).extracting(w -> w.message()).contains("封面归档服务限流，请稍后手动重试，书目信息仍可应用");
            assertThat(h.routes).hasSize(viaSocks ? 4 : 3);
            assertThat(h.routes.getLast().kind()).isEqualTo(viaSocks ? OpenLibraryClient.RouteKind.SOCKS5 : OpenLibraryClient.RouteKind.DIRECT);
            // OL-OPT-CACHE revision: a different ISBN (the first one's metadata is now cached) proves the shared cooldown.
            assertThatThrownBy(() -> h.client.lookup(isbn(1))).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getDetails()).containsEntry("reason", "COOLDOWN"));
            assertThat(h.routes).hasSize(viaSocks ? 4 : 3);
        }
    }

    @Test
    void successfulCoverSocksRouteIsPinnedAcrossTwoHopsAndNetworkFailure() {
        var h = new ProxyHarness(); h.results.add(coverMetadata()); h.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        h.results.add(redirect(301, "9273767-M.jpg")); h.results.add(redirect(302, ARCHIVE_URL));
        h.fail(OpenLibraryClient.FailureReason.RESET, false);
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_UNAVAILABLE");
        assertThat(h.routes).extracting(OpenLibraryClient.Route::kind).containsExactly(OpenLibraryClient.RouteKind.DIRECT,
                OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.SOCKS5, OpenLibraryClient.RouteKind.SOCKS5, OpenLibraryClient.RouteKind.SOCKS5);
        assertThat(h.routes.subList(2, 5)).containsOnly(h.routes.get(2));
        assertThat(h.time.get()).isEqualTo(1 + TimeUnit.SECONDS.toNanos(4));
    }

    @Test
    void redirectDirectResponseIsNotFallbackButNewResourceMayFallbackOnceOnNetworkFailure() throws Exception {
        var h = new ProxyHarness(); h.results.add(coverMetadata()); h.results.add(redirect(302, ARCHIVE_URL));
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        h.results.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
        var successful = h.client.lookup(ISBN);
        assertThat(successful.cover()).isNotNull(); assertThat(successful.cover().width()).isEqualTo(4);
        assertThat(h.routes).extracting(OpenLibraryClient.Route::kind).containsExactly(OpenLibraryClient.RouteKind.DIRECT,
                OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.SOCKS5);
        assertThat(h.requests.get(2)).isEqualTo(URI.create(ARCHIVE_URL)); assertThat(h.requests.get(3)).isEqualTo(h.requests.get(2));
    }

    @Test
    void archiveHttpErrorsAndTlsOrOversizeNeverFallbackAndInvalidImagesNeverBecomeCovers() {
        for (Object error : List.of(json(403, ""), json(500, ""), json(503, ""),
                new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.TLS, false),
                new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.BODY_TOO_LARGE, true),
                new OpenLibraryClient.Reply(200, "image/png", null, null, "<html>".getBytes()),
                new OpenLibraryClient.Reply(200, "image/png", null, null, new byte[2097153]))) {
            var h = new ProxyHarness(); h.results.add(coverMetadata()); h.results.add(redirect(302, ARCHIVE_URL)); h.results.add(error);
            var result = h.client.lookup(ISBN);
            assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).isNotEmpty(); assertThat(h.routes).hasSize(3);
            assertThat(h.routes).extracting(OpenLibraryClient.Route::kind).containsOnly(OpenLibraryClient.RouteKind.DIRECT);
        }
    }

    @Test
    void eachRedirectHopConsumesRollingWindowAndRetainsPartialBookWhenExhausted() {
        var h = new Harness();
        for (int i = 0; i < 57; i++) { h.replies.add(json(404, "")); h.client.lookup(isbn(i)); }
        h.replies.add(coverMetadata()); h.replies.add(redirect(301, "9273767-M.jpg")); h.replies.add(redirect(302, ARCHIVE_URL));
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_RATE_LIMITED");
        assertThat(result.retryAfterSeconds()).isBetween(1, 300); assertThat(h.requests).hasSize(60);
        assertThat(h.requests.getLast().getHost()).isEqualTo("covers.openlibrary.org");
    }

    @Test
    void redirectSharesFourteenSecondDeadlineAndSixSecondAttemptBudget() {
        var h = new ProxyHarness(); h.onAttempt = () -> h.time.addAndGet(TimeUnit.SECONDS.toNanos(6));
        h.results.add(coverMetadata()); h.results.add(redirect(302, ARCHIVE_URL)); h.results.add(json(200, "too late"));
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_UNAVAILABLE");
        assertThat(h.deadlines).containsExactly(Duration.ofSeconds(6), Duration.ofSeconds(6), Duration.ofSeconds(2));
        var exhausted = new ProxyHarness(); exhausted.onAttempt = () -> exhausted.time.addAndGet(TimeUnit.SECONDS.toNanos(7));
        exhausted.results.add(coverMetadata()); exhausted.results.add(redirect(302, ARCHIVE_URL));
        assertThat(exhausted.client.lookup(ISBN).fields().title()).isEqualTo("book");
        assertThat(exhausted.requests).hasSize(2);
    }

    @Test
    void cancellationAfterRedirectNeverSendsAnotherHop() {
        var h = new ProxyHarness(); h.results.add(coverMetadata()); h.results.add(redirect(302, ARCHIVE_URL));
        h.onAttempt = () -> { if (h.routes.size() == 2) { Thread.currentThread().interrupt(); } };
        try {
            var result = h.client.lookup(ISBN);
            assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_UNAVAILABLE"); assertThat(h.requests).hasSize(2);
        } finally { Thread.interrupted(); }
    }

    @Test
    void redirectWaitsForCleanupAndRetainsPermitUntilChannelsAreReclaimed() {
        var cleanup = new CompletableFuture<Void>(); var calls = new AtomicLong(); var time = new AtomicLong(1);
        var transport = new OpenLibraryClient.Transport() {
            public OpenLibraryClient.Reply get(URI uri, int max, Duration timeout) {
                return calls.incrementAndGet() == 1 ? coverMetadata() : redirect(302, ARCHIVE_URL);
            }
            public java.util.concurrent.CompletionStage<Void> quiescence() {
                return calls.get() < 2 ? CompletableFuture.completedFuture(null) : cleanup;
            }
        };
        var client = new OpenLibraryClient(transport, time::get, time::addAndGet, new CoverImageValidator());
        var result = client.lookup(ISBN); assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_UNAVAILABLE");
        assertThatThrownBy(() -> client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "BUSY"));
        assertThat(calls.get()).isEqualTo(2); cleanup.complete(null);
    }

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
        // OL-OPT-API revision: Edition (redirect hop) + verified Search authors replace the Legacy jscmd=data payload.
        h.replies.add(redirect(302, "/books/OL7353617M.json"));
        h.replies.add(json(200, "{\"key\":\"/books/OL7353617M\",\"isbn_10\":[\"" + ISBN + "\"],\"title\":\"  标题  \",\"authors\":[{\"key\":\"/authors/OL1A\"},{\"key\":\"/authors/OL2A\"}],"
                + "\"works\":[{\"key\":\"/works/OL45804W\"}],\"publishers\":[\"社\"],\"publish_date\":\"October 1998\",\"description\":\" 简介 \",\"covers\":[123]}"));
        h.replies.add(json(200, "{\"docs\":[{\"key\":\"/works/OL45804W\",\"author_key\":[\"OL1A\",\"OL2A\"],\"author_name\":[\"A\",\"B\"]}]}"));
        byte[] png = BookCoverApiTest.image("png", 4, 6);
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, png));
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
                URI.create("https://openlibrary.org/isbn/" + ISBN + ".json"),
                URI.create("https://openlibrary.org/books/OL7353617M.json"),
                URI.create("https://openlibrary.org/search.json?q=isbn:" + ISBN + "&fields=key,author_key,author_name&limit=1"),
                URI.create("https://covers.openlibrary.org/b/id/123-M.jpg?default=false"));
        assertThat(h.time.get()).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(3));
        assertThat(h.deadlines).allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(Duration.ofSeconds(6)));
    }

    @Test
    void acOl04MissingMatchingKeyAndMetadata404AreNotFoundWithoutCoverAttempt() {
        // OL-OPT-API revision: an Edition for another ISBN / without key is a mismatch, never borrowed data.
        for (var reply : List.of(json(404, "not found"), json(200, "{}"), json(200, "{\"key\":\"/books/OL1M\",\"isbn_10\":[\"0306406152\"],\"title\":\"wrong\"}"))) {
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
                + "\"publishers\":[\"" + "x".repeat(101) + "\"],\"publish_date\":\"1998 / 2000\","
                + "\"description\":true,\"covers\":\"bad\"}")));
        var result = h.client.lookup(ISBN);
        assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isNull(); assertThat(result.fields().author()).isNull();
        assertThat(result.fields().publisher()).isNull(); assertThat(result.fields().description()).isNull(); assertThat(result.fields().publishYear()).isNull();
        assertThat(result.warnings()).hasSize(6); assertThat(h.requests).hasSize(1);
        assertThat(result.warnings()).extracting(w -> w.field()).contains("title", "author", "publisher", "description", "publishYear", "cover");
    }

    @Test
    void dod02RejectsNonPermittedReferencesWithoutFallbackRequest() {
        // OL-OPT-API revision: Edition covers are numeric IDs; only a positive integral ID ever becomes the fixed covers URL.
        for (String covers : new String[]{"[\"http://covers.openlibrary.org/b/id/1-M.jpg\"]", "[\"https://evil.example/b/id/1-M.jpg\"]",
                "[\"1\"]", "[1.5]", "[999999999999999999999]", "[{\"id\":1}]", "\"https://covers.openlibrary.org/b/id/1-M.jpg\"", "true"}) {
            var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\",\"covers\":" + covers + "}")));
            var result = h.client.lookup(ISBN);
            assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).isNotEmpty(); assertThat(h.requests).hasSize(1);
        }
        for (String covers : new String[]{"[0]", "[-1]", "[]"}) {
            var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\",\"covers\":" + covers + "}")));
            var result = h.client.lookup(ISBN);
            assertThat(result.cover()).isNull(); assertThat(result.warnings()).isEmpty(); assertThat(h.requests).hasSize(1);
        }
    }

    @Test
    void acOl04CoverErrorsDoNotDiscardSuccessfulBookFieldsAndNeverRetry() {
        for (var cover : List.of(json(302, "redirect"), json(500, "bad"), new OpenLibraryClient.Reply(200, "image/png", null, null, "<html>".getBytes()),
                new OpenLibraryClient.Reply(200, "image/gif", null, null, new byte[]{1}), new OpenLibraryClient.Reply(200, "image/png", null, null, new byte[2097153]))) {
            var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\"}"))); h.replies.add(cover);
            var result = h.client.lookup(ISBN);
            assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
            assertThat(result.warnings()).isNotEmpty(); assertThat(h.requests).hasSize(2);
        }
    }

    @Test
    void acOl04MetadataMalformedRedirectTooLargeAndFailuresBecome502() {
        for (var meta : List.of(json(200, "invalid JSON"), json(200, "[]"), json(200, "\"bad\""), json(302, ""), json(503, ""),
                new OpenLibraryClient.Reply(200, "application/json", null, null, new byte[256 * 1024 + 1]))) {
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
        for (int i = 0; i < 59; i++) { h.replies.add(json(404, "")); h.client.lookup(isbn(i)); }
        h.replies.add(json(200, book("{\"title\":\"book\"}")));
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().title()).isEqualTo("book"); assertThat(result.cover()).isNull();
        assertThat(result.retryAfterSeconds()).isBetween(1, 300);
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_RATE_LIMITED");
        assertThat(h.requests).hasSize(60);
    }

    @Test
    void dod02Metadata429EnforcesSanitizedCooldownAndNoAutomaticRetry() {
        // OL-OPT-POLICY revision: valid Retry-After values are honoured in full (301 no longer truncated to the old 60/300 default);
        // "0" keeps the 1 s request spacing floor; missing/invalid stays 60 s.
        for (String raw : new String[]{null, "bad", "0", "301", "Wed, 21 Oct 2026", " 5 "}) {
            var h = new Harness(); h.replies.add(new OpenLibraryClient.Reply(429, "application/json", raw, null, new byte[0]));
            int expected = " 5 ".equals(raw) ? 5 : "301".equals(raw) ? 301 : "0".equals(raw) ? 1 : 60;
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
        h.replies.add(new OpenLibraryClient.Reply(429, "image/jpeg", "7", null, new byte[0]));
        var result = h.client.lookup(ISBN);
        assertThat(result.found()).isTrue(); assertThat(result.fields().title()).isEqualTo("book");
        assertThat(result.retryAfterSeconds()).isEqualTo(7); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("OPEN_LIBRARY_RATE_LIMITED");
        assertThatThrownBy(() -> h.client.lookup(isbn(1))).isInstanceOf(ApiException.class);
        assertThat(h.requests).hasSize(2);
    }

    @Test
    void dod02RollingWindowLimitsAllRealExchangesAndThenRecovers() {
        var h = new Harness();
        for (int i = 0; i < 60; i++) { h.replies.add(json(404, "")); assertThat(h.client.lookup(isbn(i)).found()).isFalse(); }
        assertThat(h.requests).hasSize(60);
        assertThatThrownBy(() -> h.client.lookup(isbn(60))).isInstanceOfSatisfying(ApiException.class,
                e -> { assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED); assertThat((Integer)e.getDetails().get("retryAfterSeconds")).isBetween(1, 300); });
        h.time.addAndGet(TimeUnit.MINUTES.toNanos(5)); h.replies.add(json(404, ""));
        assertThat(h.client.lookup(isbn(61)).found()).isFalse(); assertThat(h.requests).hasSize(61);
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
        final OpenLibraryClient client;
        ProxyHarness() { this(Duration.ofSeconds(30)); }
        ProxyHarness(Duration directFailureTtl) {
            client = new OpenLibraryClient((OpenLibraryClient.RoutedTransport) (uri, route, max, timeout) -> {
                routes.add(route); requests.add(uri); deadlines.add(timeout); onAttempt.run();
                Object next = results.removeFirst();
                if (next instanceof IOException failure) { throw failure; }
                return (OpenLibraryClient.Reply) next;
            }, time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator(), settings::get, directFailureTtl);
        }
        void fail(OpenLibraryClient.FailureReason reason, boolean headers) { results.add(new OpenLibraryClient.TransportFailure(reason, headers)); }
        List<OpenLibraryClient.RouteKind> kinds() { return routes.stream().map(OpenLibraryClient.Route::kind).toList(); }
    }

    // ---- OL-OPT-POLICY-01 ----
    @Test
    void policyLearnsOnlyAfterSocksServesTrustedResourceThenSkipsDirectFor30sThenSingleHalfOpenProbe() {
        var h = new ProxyHarness();
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).found()).isFalse();
        h.routes.clear(); h.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        h.results.add(json(404, ""));
        var skipped = h.client.lookup(isbn(1));
        assertThat(skipped.found()).isFalse();
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.SOCKS5);
        h.routes.clear(); h.time.addAndGet(TimeUnit.SECONDS.toNanos(30));
        h.results.add(json(404, "")); h.client.lookup(isbn(2));
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
        h.routes.clear(); h.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        h.results.add(json(404, "")); h.client.lookup(isbn(3));
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
    }

    @Test
    void policyHalfOpenFailureFallsBackOnceAndRelearns() {
        var h = new ProxyHarness();
        h.fail(OpenLibraryClient.FailureReason.DNS, false); h.results.add(json(404, "")); h.client.lookup(ISBN);
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(31)); h.routes.clear();
        h.fail(OpenLibraryClient.FailureReason.DNS, false); h.results.add(json(404, "")); h.client.lookup(isbn(1));
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.SOCKS5);
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); h.routes.clear();
        h.results.add(json(404, "")); h.client.lookup(isbn(2));
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.SOCKS5);
    }

    @Test
    void policyTlsHandshakeTimeoutHttpHeadersAndProxyFailureNeverLearn() {
        for (Object directFailure : List.of(new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.TLS, false),
                new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.HANDSHAKE_TIMEOUT, false),
                new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.RESPONSE_TIMEOUT, false),
                new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.RESET, true, 200, null),
                new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.PROTOCOL, false))) {
            var h = new ProxyHarness(); h.results.add(directFailure);
            var failure = (OpenLibraryClient.TransportFailure) directFailure;
            if (failure.fallbackAllowed()) { h.results.add(json(404, "")); h.client.lookup(ISBN); }
            else { assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class); }
            h.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); h.routes.clear(); h.results.add(json(404, ""));
            h.client.lookup(isbn(1));
            assertThat(h.kinds()).as(failure.reason.name()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
        }
        var proxyDown = new ProxyHarness(); proxyDown.fail(OpenLibraryClient.FailureReason.CONNECT, false); proxyDown.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        assertThatThrownBy(() -> proxyDown.client.lookup(ISBN)).isInstanceOf(ApiException.class);
        proxyDown.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); proxyDown.routes.clear(); proxyDown.results.add(json(404, ""));
        proxyDown.client.lookup(isbn(1));
        assertThat(proxyDown.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
        var http = new ProxyHarness(); http.fail(OpenLibraryClient.FailureReason.CONNECT, false); http.results.add(json(503, ""));
        assertThatThrownBy(() -> http.client.lookup(ISBN)).isInstanceOf(ApiException.class);
        http.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); http.routes.clear(); http.results.add(json(404, ""));
        http.client.lookup(isbn(1));
        assertThat(http.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
    }

    @Test
    void policyRevisionChangeOrDisabledProxyGoesDirectAndTtlZeroNeverSkips() {
        var h = new ProxyHarness(); h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(json(404, "")); h.client.lookup(ISBN);
        h.settings.set(new com.example.library.common.proxy.ProxySnapshot(true, "localhost", 9999, 2, null));
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); h.routes.clear(); h.results.add(json(404, "")); h.client.lookup(isbn(1));
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
        var disabled = new ProxyHarness(); disabled.fail(OpenLibraryClient.FailureReason.CONNECT, false); disabled.results.add(json(404, "")); disabled.client.lookup(ISBN);
        disabled.settings.set(new com.example.library.common.proxy.ProxySnapshot(false, null, null, 1, null));
        disabled.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); disabled.routes.clear(); disabled.results.add(json(404, "")); disabled.client.lookup(isbn(1));
        assertThat(disabled.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
        var off = new ProxyHarness(Duration.ZERO); off.fail(OpenLibraryClient.FailureReason.CONNECT, false); off.results.add(json(404, "")); off.client.lookup(ISBN);
        off.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); off.routes.clear(); off.fail(OpenLibraryClient.FailureReason.CONNECT, false); off.results.add(json(404, ""));
        off.client.lookup(isbn(1));
        assertThat(off.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT, OpenLibraryClient.RouteKind.SOCKS5);
    }

    @Test
    void policyCoversIsbn403IsLabelledPossibleRateLimitAndCoolsIsbnCoversWithoutOutbound() {
        var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\"}"))); h.replies.add(json(403, ""));
        var first = h.client.lookup(ISBN);
        assertThat(first.fields().title()).isEqualTo("book"); assertThat(first.retryAfterSeconds()).isEqualTo(60);
        assertThat(first.warnings()).extracting(w -> w.code()).containsExactly("COVER_REJECTED");
        assertThat(first.warnings().getFirst().message()).contains("拒绝", "可能");
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); h.replies.add(json(200, book(isbn(1), "{\"title\":\"again\"}")));
        var second = h.client.lookup(isbn(1));
        assertThat(second.fields().title()).isEqualTo("again"); assertThat(second.warnings()).extracting(w -> w.code()).containsExactly("COVER_REJECTED");
        assertThat(h.requests).hasSize(3);
        var byId = new Harness(); byId.replies.add(coverMetadata()); byId.replies.add(json(403, ""));
        var other = byId.client.lookup(ISBN);
        assertThat(other.warnings()).extracting(w -> w.code()).containsExactly("COVER_UNAVAILABLE"); assertThat(other.retryAfterSeconds()).isNull();
    }

    @Test
    void policyHugeRetryAfterOnCoverReturnsNullSecondsWithLongWaitWarning() {
        var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"book\"}")));
        h.replies.add(new OpenLibraryClient.Reply(429, "image/jpeg", "9".repeat(30), null, new byte[0]));
        var result = h.client.lookup(ISBN);
        assertThat(result.retryAfterSeconds()).isNull();
        assertThat(result.warnings().getFirst().details()).containsEntry("longWait", true);
        assertThatThrownBy(() -> h.client.lookup(isbn(1))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("longWait", true).doesNotContainKey("retryAfterSeconds"));
    }

    // ---- OL-OPT-CACHE-01 ----
    @Test
    void cacheServesMetadataAndValidatedCoverWithoutOutboundOrSpacingEvenDuringCooldown() throws Exception {
        var h = new Harness(); h.replies.add(coverMetadata());
        byte[] png = BookCoverApiTest.image("png", 4, 6);
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, png));
        var first = h.client.lookup(ISBN);
        assertThat(first.cover()).isNotNull(); assertThat(h.requests).hasSize(2);
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        h.replies.add(new OpenLibraryClient.Reply(429, "application/json", "120", null, new byte[0]));
        assertThatThrownBy(() -> h.client.lookup(isbn(1))).isInstanceOf(ApiException.class);
        long before = h.time.get();
        var again = h.client.lookup(ISBN);
        assertThat(again).isEqualTo(first);
        assertThat(again.cover().base64()).isEqualTo(first.cover().base64());
        assertThat(h.requests).hasSize(3); assertThat(h.time.get()).isEqualTo(before);
        assertThat(h.client.cached(ISBN)).contains(first); assertThat(h.client.cached(isbn(2))).isEmpty();
        var covers = (OpenLibraryLookupState.TtlCache<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(h.client, "covers");
        assertThat(covers.bytes()).isEqualTo(png.length); // raw bytes only, no Base64 copy retained
    }

    @Test
    void closeDuringPartialLookupNeverRefillsClearedCaches() {
        var holder = new java.util.concurrent.atomic.AtomicReference<OpenLibraryClient>();
        var time = new AtomicLong(1);
        var client = new OpenLibraryClient((uri, max, timeout) -> {
            if (uri.getHost().equals("openlibrary.org")) { return coverMetadata(); }
            holder.get().close();
            throw new IOException("closed during cover");
        }, time::get, time::addAndGet, new CoverImageValidator());
        holder.set(client);
        assertThat(client.lookup(ISBN).fields().title()).isEqualTo("book");
        var metadata = (OpenLibraryLookupState.TtlCache<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(client, "metadata");
        assertThat(metadata.size()).isZero();
    }

    @Test
    void cacheTrustedNotFoundForSixtySecondsAndNeverCachesFailures() {
        var h = new Harness(); h.replies.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).found()).isFalse();
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertThat(h.client.lookup(ISBN).found()).isFalse(); assertThat(h.requests).hasSize(1);
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(1)); h.replies.add(json(404, ""));
        assertThat(h.client.lookup(ISBN).found()).isFalse(); assertThat(h.requests).hasSize(2);
        for (var failure : List.of(json(503, ""), json(200, "invalid JSON"), json(403, ""))) {
            var f = new Harness(); f.replies.add(failure);
            assertThatThrownBy(() -> f.client.lookup(ISBN)).isInstanceOf(ApiException.class);
            f.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); f.replies.add(json(200, book("{\"title\":\"book\"}"))); f.replies.add(json(404, ""));
            assertThat(f.client.lookup(ISBN).fields().title()).isEqualTo("book"); assertThat(f.requests).hasSize(3);
        }
    }

    @Test
    void cacheCoverFailuresAreRetriedButCover404IsShortNegativeAndMetadataStaysCached() throws Exception {
        var h = new Harness(); h.replies.add(coverMetadata()); h.replies.add(json(500, ""));
        assertThat(h.client.lookup(ISBN).warnings()).isNotEmpty();
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
        assertThat(h.client.lookup(ISBN).cover()).isNotNull();
        assertThat(h.requests).hasSize(3); assertThat(h.requests.get(2).getHost()).isEqualTo("covers.openlibrary.org");
        var negative = new Harness(); negative.replies.add(json(200, book("{\"title\":\"book\"}"))); negative.replies.add(json(404, ""));
        negative.client.lookup(ISBN);
        negative.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertThat(negative.client.lookup(ISBN).fields().title()).isEqualTo("book"); assertThat(negative.requests).hasSize(2);
        negative.time.addAndGet(TimeUnit.SECONDS.toNanos(60)); negative.replies.add(json(404, ""));
        negative.client.lookup(ISBN);
        assertThat(negative.requests).hasSize(3); assertThat(negative.requests.get(2).getHost()).isEqualTo("covers.openlibrary.org");
    }

    @Test
    void cacheSuccessTtlIs24HoursWithoutRenewalAndCloseClearsEverything() throws Exception {
        var h = new Harness(); h.replies.add(coverMetadata());
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
        h.client.lookup(ISBN);
        h.time.addAndGet(TimeUnit.HOURS.toNanos(24) - TimeUnit.SECONDS.toNanos(1) - 1);
        h.client.lookup(ISBN); assertThat(h.requests).hasSize(2);
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        h.replies.add(coverMetadata()); h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
        h.client.lookup(ISBN); assertThat(h.requests).hasSize(4);
        assertThat(h.client.cached(ISBN)).isPresent();
        h.client.close();
        assertThat(h.client.cached(ISBN)).isEmpty();
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
        h.results.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
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
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(new OpenLibraryClient.Reply(429, "application/json", "5", null, new byte[0]));
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
            return call == 2 ? json(200, book("{\"title\":\"book\"}")) : new OpenLibraryClient.Reply(200, "image/png", null, null, image);
        }, time::get, time::addAndGet, new CoverImageValidator(), () -> { reads.incrementAndGet(); return settings.get(); });
        assertThat(client.lookup(ISBN).cover().width()).isEqualTo(4); assertThat(reads.get()).isEqualTo(1);
        assertThat(hosts).containsExactly("localhost", "localhost");
    }

    @Test
    void acProxy06ProxyAttemptsCountTowardSharedRollingLimitAndBudget() {
        // OL-OPT-POLICY revision: direct-failure memory disabled (TTL 0) so every lookup still pays DIRECT+SOCKS and both count.
        var h = new ProxyHarness(Duration.ZERO);
        for (int i = 0; i < 30; i++) {
            h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(json(404, "")); assertThat(h.client.lookup(isbn(i)).found()).isFalse();
        }
        assertThatThrownBy(() -> h.client.lookup(isbn(30))).isInstanceOfSatisfying(ApiException.class,
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
        assertThatThrownBy(() -> client.lookup(isbn(1))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "TIMEOUT").containsEntry("stage", "METADATA"));
        assertThat(calls.get()).isEqualTo(1);
        // OL-OPT-POLICY revision: the shared window moved from the client into OpenLibraryRequestPolicy (same assertion).
        Object policy = org.springframework.test.util.ReflectionTestUtils.getField(client, "policy");
        assertThat((java.util.ArrayDeque<?>) org.springframework.test.util.ReflectionTestUtils.getField(policy, "exchanges")).hasSize(1);
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

    // ---- OL-OPT-DIAG-01 ----
    @Test
    void diagUpstream401And403BecomeLocal502WithRealStatusAndNoAuthSemantics() {
        for (int status : new int[]{401, 403, 500}) {
            var h = new Harness(); h.replies.add(json(status, "{\"error\":\"secret body\"}"));
            assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_UNAVAILABLE);
                assertThat(e.getCode().getStatus().value()).isEqualTo(502);
                assertThat(e.getDetails()).containsEntry("reason", "HTTP_ERROR").containsEntry("upstreamStatus", status)
                        .containsEntry("stage", "METADATA").containsEntry("provider", "OPEN_LIBRARY")
                        .containsEntry("lastRoute", "DIRECT").containsEntry("proxyOutcome", "DISABLED");
                assertThat(e.getDetails().toString()).doesNotContain("secret body");
            });
        }
    }

    @Test
    void diagTransmissionFailureHasNoGuessedUpstreamStatusButHeaderFailureKeepsIt() {
        var none = new ProxyHarness(); none.fail(OpenLibraryClient.FailureReason.CONNECT, false); none.fail(OpenLibraryClient.FailureReason.RESET, false);
        assertThatThrownBy(() -> none.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getDetails()).containsEntry("upstreamStatus", tools.jackson.databind.node.NullNode.getInstance())
                    .containsEntry("proxyOutcome", "FAILED");
        });
        var headers = new ProxyHarness();
        headers.results.add(new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.TIMEOUT, true, 200, null));
        assertThatThrownBy(() -> headers.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getDetails()).containsEntry("upstreamStatus", 200).containsEntry("reason", "TIMEOUT")
                    .containsEntry("proxyOutcome", "NOT_ATTEMPTED");
        });
        assertThat(headers.routes).hasSize(1);
    }

    @Test
    void diagSocksHttpResponseIsRespondedNotFailed() {
        var h = new ProxyHarness(); h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(json(503, ""));
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getDetails()).containsEntry("lastRoute", "SOCKS5").containsEntry("proxyOutcome", "RESPONDED")
                    .containsEntry("upstreamStatus", 503).containsEntry("directReason", "CONNECT");
        });
    }

    @Test
    void headerFailureAnd429KeepStatusRetryAfterAndDiagnosticContext() {
        var headers = new ProxyHarness();
        headers.results.add(new OpenLibraryClient.TransportFailure(OpenLibraryClient.FailureReason.TIMEOUT, true, 503, "301"));
        assertThatThrownBy(() -> headers.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("upstreamStatus", 503));
        assertThatThrownBy(() -> headers.client.lookup(isbn(1))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "COOLDOWN").containsEntry("retryAfterSeconds", 301));
        var rate = new ProxyHarness(); rate.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        rate.results.add(new OpenLibraryClient.Reply(429, "application/json", "301", null, new byte[0]));
        assertThatThrownBy(() -> rate.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("upstreamStatus", 429).containsEntry("stage", "METADATA")
                        .containsEntry("provider", "OPEN_LIBRARY").containsEntry("proxyOutcome", "RESPONDED"));
    }

    @Test
    void diagCoverWarningsCarryStageProviderStatusAndRoute() {
        var covers = new Harness(); covers.replies.add(coverMetadata()); covers.replies.add(json(500, ""));
        var warning = covers.client.lookup(ISBN).warnings().getFirst();
        assertThat(warning.details()).containsEntry("stage", "COVER").containsEntry("provider", "COVERS")
                .containsEntry("upstreamStatus", 500).containsEntry("lastRoute", "DIRECT");
        var archive = new Harness(); archive.replies.add(coverMetadata()); archive.replies.add(redirect(302, ARCHIVE_URL)); archive.replies.add(json(503, ""));
        assertThat(archive.client.lookup(ISBN).warnings().getFirst().details()).containsEntry("provider", "ARCHIVE").containsEntry("upstreamStatus", 503);
        var rejected = new Harness(); rejected.replies.add(coverMetadata()); rejected.replies.add(redirect(302, "https://evil.example/x"));
        var redirectWarning = rejected.client.lookup(ISBN).warnings().getFirst();
        assertThat(redirectWarning.details()).containsEntry("reason", "COVER_REDIRECT_REJECTED").containsEntry("upstreamStatus", 302);
        assertThat(redirectWarning.details().toString()).doesNotContain("evil.example");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void diagApplicationLogIsSanitizedEnumOnly(org.springframework.boot.test.system.CapturedOutput output) {
        var h = new ProxyHarness();
        h.settings.set(new com.example.library.common.proxy.ProxySnapshot(true, "secret-proxy.example", 9999, 1, null));
        h.results.add(new IOException("raw secret-proxy.example:9999 cookie=token"));
        assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOf(ApiException.class);
        assertThat(output.getAll()).contains("Open Library", "reason=NETWORK", "stage=METADATA")
                .doesNotContain(ISBN, "secret-proxy", "9999", "cookie", "token", "IOException");
    }

    // ---- OL-OPT-API-01 ----
    private static OpenLibraryClient.Reply edition(String key, String isbnField, String extra) {
        return json(200, "{\"key\":\"" + key + "\"," + isbnField + (extra.isEmpty() ? "" : "," + extra) + "}");
    }

    @Test
    void apiFollowsExactlyOneSameOriginEditionRedirectAndValidatesItsKey() {
        for (String location : new String[]{"/books/OL7353617M.json", "https://openlibrary.org/books/OL7353617M.json"}) {
            var h = new Harness(); h.replies.add(redirect(302, location));
            h.replies.add(edition("/books/OL7353617M", "\"isbn_10\":[\"" + ISBN + "\"]", "\"title\":\"ok\",\"covers\":[]"));
            assertThat(h.client.lookup(ISBN).fields().title()).isEqualTo("ok");
            assertThat(h.requests).containsExactly(URI.create("https://openlibrary.org/isbn/" + ISBN + ".json"),
                    URI.create("https://openlibrary.org/books/OL7353617M.json"));
        }
        var mismatch = new Harness(); mismatch.replies.add(redirect(302, "/books/OL1M.json"));
        mismatch.replies.add(edition("/books/OL2M", "\"isbn_10\":[\"" + ISBN + "\"]", "\"title\":\"other\",\"covers\":[1]"));
        var result = mismatch.client.lookup(ISBN);
        assertThat(result.found()).isFalse(); assertThat(result.fields().title()).isNull(); assertThat(result.cover()).isNull();
        assertThat(result.warnings()).extracting(w -> w.code()).containsExactly("EDITION_MISMATCH");
        assertThat(mismatch.requests).hasSize(2);
        var again = new Harness(); again.replies.add(redirect(302, "/books/OL1M.json")); again.replies.add(redirect(302, "/books/OL2M.json"));
        assertThatThrownBy(() -> again.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "METADATA_REDIRECT_LIMIT"));
        assertThat(again.requests).hasSize(2);
    }

    @Test
    void apiRejectsEveryOtherMetadataRedirectWithoutRequestOrDisclosure() {
        for (String location : List.of("https://evil.example/books/OL1M.json", "http://openlibrary.org/books/OL1M.json",
                "https://openlibrary.org:8443/books/OL1M.json", "https://u@openlibrary.org/books/OL1M.json", "/books/OL1M.json?x=1",
                "/books/OL1M.json#f", "/works/OL1W.json", "/books/OL1M", "/books/OL0M.json", "/books/../isbn/1.json", "/books/OL1M%2Ejson",
                "/books/./OL1M.json", "//evil.example/books/OL1M.json", "/books/OL12345678901M.json", "/api/books?bibkeys=ISBN:" + ISBN)) {
            var h = new Harness(); h.replies.add(redirect(302, location));
            assertThatThrownBy(() -> h.client.lookup(ISBN)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getDetails()).containsEntry("reason", "METADATA_REDIRECT_REJECTED").containsEntry("upstreamStatus", 302);
                assertThat(e.getDetails().toString()).doesNotContain("evil", "OL1M");
            });
            assertThat(h.requests).as(location).hasSize(1);
        }
    }

    @Test
    void apiIsbnEquivalenceAccepts978ConversionOnlyAndMismatchUsesNoFields() {
        var ten = new Harness(); ten.replies.add(edition("/books/OL1M", "\"isbn_13\":[\"9780804429573\"]", "\"title\":\"t\",\"covers\":[]"));
        assertThat(ten.client.lookup(ISBN).found()).isTrue();
        var thirteen = new Harness(); thirteen.replies.add(edition("/books/OL1M", "\"isbn_10\":[\"" + ISBN + "\"]", "\"title\":\"t\",\"covers\":[]"));
        assertThat(thirteen.client.lookup("9780804429573").fields().title()).isEqualTo("t");
        var b979 = new Harness(); b979.replies.add(edition("/books/OL1M", "\"isbn_10\":[\"1234567897\"]", "\"title\":\"wrong\",\"covers\":[5]"));
        var result = b979.client.lookup("9791234567896");
        assertThat(result.found()).isFalse(); assertThat(result.fields().title()).isNull(); assertThat(b979.requests).hasSize(1);
        assertThat(result.isbn()).isEqualTo("9791234567896");
    }

    @Test
    void apiAuthorsComeOnlyFromVerifiedSearchAndFailuresKeepPartialMetadata() {
        String refs = "\"title\":\"t\",\"description\":\"版本简介\",\"covers\":[],\"authors\":[{\"key\":\"/authors/OL1A\"}],\"works\":[{\"key\":\"/works/OL9W\"}]";
        var unverified = new Harness(); unverified.replies.add(json(200, book("{" + refs + "}")));
        unverified.replies.add(json(200, "{\"docs\":[{\"key\":\"/works/OL8W\",\"author_key\":[\"OL1A\"],\"author_name\":[\"冒名\"]}]}"));
        var result = unverified.client.lookup(ISBN);
        assertThat(result.fields().author()).isNull(); assertThat(result.fields().title()).isEqualTo("t");
        assertThat(result.warnings()).extracting(w -> w.code()).containsExactly("AUTHOR_UNVERIFIED");
        var failed = new Harness(); failed.replies.add(json(200, book("{" + refs + "}"))); failed.replies.add(json(500, ""));
        var partial = failed.client.lookup(ISBN);
        assertThat(partial.fields().title()).isEqualTo("t"); assertThat(partial.fields().author()).isNull();
        assertThat(partial.warnings()).extracting(w -> w.code()).containsExactly("AUTHOR_UNAVAILABLE");
        assertThat(partial.warnings().getFirst().details()).containsEntry("upstreamStatus", 500);
        // transient optional failure is not cached: the next lookup asks again
        failed.time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        failed.replies.add(json(200, book("{" + refs + "}")));
        failed.replies.add(json(200, "{\"docs\":[{\"key\":\"/works/OL9W\",\"author_key\":[\"OL1A\"],\"author_name\":[\"真作者\"]}]}"));
        assertThat(failed.client.lookup(ISBN).fields().author()).isEqualTo("真作者");
        var noWork = new Harness(); noWork.replies.add(json(200, book("{\"covers\":[],\"authors\":[{\"key\":\"/authors/OL1A\"}]}")));
        assertThat(noWork.client.lookup(ISBN).warnings()).extracting(w -> w.code()).containsExactly("AUTHOR_UNVERIFIED");
        assertThat(noWork.requests).hasSize(1);
    }

    @Test
    void apiWorkDescriptionOnlyAfterCoverForSingleVerifiedWorkWithinBudget() throws Exception {
        var h = new Harness(); h.replies.add(json(200, book("{\"title\":\"t\",\"covers\":[77],\"notes\":\"不是简介\",\"works\":[{\"key\":\"/works/OL45804W\"}]}")));
        h.replies.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));
        h.replies.add(json(200, "{\"key\":\"/works/OL45804W\",\"description\":{\"type\":\"/type/text\",\"value\":\"作品级简介\"}}"));
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().description()).isEqualTo("作品级简介"); assertThat(result.cover()).isNotNull();
        assertThat(result.warnings()).extracting(w -> w.code()).containsExactly("DESCRIPTION_FROM_WORK");
        assertThat(h.requests.get(1).getHost()).isEqualTo("covers.openlibrary.org");
        assertThat(h.requests.get(2)).isEqualTo(URI.create("https://openlibrary.org/works/OL45804W.json"));
        var wrongKey = new Harness(); wrongKey.replies.add(json(200, book("{\"covers\":[],\"works\":[{\"key\":\"/works/OL1W\"}]}")));
        wrongKey.replies.add(json(200, "{\"key\":\"/works/OL2W\",\"description\":\"别的作品\"}"));
        assertThat(wrongKey.client.lookup(ISBN).fields().description()).isNull();
        var many = new Harness(); many.replies.add(json(200, book("{\"covers\":[],\"works\":[{\"key\":\"/works/OL1W\"},{\"key\":\"/works/OL2W\"}]}")));
        assertThat(many.client.lookup(ISBN).fields().description()).isNull(); assertThat(many.requests).hasSize(1);
        var own = new Harness(); own.replies.add(json(200, book("{\"covers\":[],\"description\":\"版本简介\",\"works\":[{\"key\":\"/works/OL1W\"}]}")));
        assertThat(own.client.lookup(ISBN).fields().description()).isEqualTo("版本简介"); assertThat(own.requests).hasSize(1);
        var slow = new ProxyHarness(); slow.onAttempt = () -> slow.time.addAndGet(TimeUnit.SECONDS.toNanos(6));
        slow.results.add(json(200, book("{\"title\":\"t\",\"covers\":[1],\"works\":[{\"key\":\"/works/OL1W\"}]}"))); slow.results.add(json(404, ""));
        var skipped = slow.client.lookup(ISBN);
        assertThat(skipped.fields().title()).isEqualTo("t"); assertThat(slow.requests).hasSize(2);
        assertThat(skipped.warnings()).extracting(w -> w.code()).containsExactly("DESCRIPTION_SKIPPED");
    }

    @Test
    void invalidEditionDescriptionDoesNotFallBackToWorkAndWrongWorkNeverLearnsDirectFailure() {
        var invalid = new Harness();
        invalid.replies.add(json(200, book("{\"covers\":[],\"description\":\"" + "d".repeat(2001) + "\",\"works\":[{\"key\":\"/works/OL1W\"}]}")));
        invalid.replies.add(json(200, "{\"key\":\"/works/OL1W\",\"description\":\"must not be borrowed\"}"));
        var omitted = invalid.client.lookup(ISBN);
        assertThat(omitted.fields().description()).isNull();
        assertThat(invalid.requests).hasSize(1);
        var wrongWork = new ProxyHarness();
        wrongWork.results.add(json(200, book("{\"covers\":[],\"works\":[{\"key\":\"/works/OL1W\"}]}")));
        wrongWork.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        wrongWork.results.add(json(200, "{\"key\":\"/works/OL2W\",\"description\":\"wrong\"}"));
        assertThat(wrongWork.client.lookup(ISBN).fields().description()).isNull();
        wrongWork.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); wrongWork.routes.clear();
        wrongWork.results.add(json(404, ""));
        wrongWork.client.lookup(isbn(1));
        assertThat(wrongWork.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
    }

    @Test
    void invalidSearchPayloadIsPartialButCannotLearnDirectFailure() {
        var h = new ProxyHarness();
        h.results.add(json(200, book("{\"title\":\"book\",\"description\":\"Edition text\",\"covers\":[],\"authors\":[{\"key\":\"/authors/OL1A\"}],\"works\":[{\"key\":\"/works/OL1W\"}]}")));
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(json(200, "[]"));
        assertThat(h.client.lookup(ISBN).fields().title()).isEqualTo("book");
        h.time.addAndGet(TimeUnit.SECONDS.toNanos(2)); h.routes.clear(); h.results.add(json(404, ""));
        h.client.lookup(isbn(1));
        assertThat(h.kinds()).containsExactly(OpenLibraryClient.RouteKind.DIRECT);
    }

    @Test
    void apiAtMostEightRealAttemptsPerQueryCountingFallbacksRedirectsAndHops() throws Exception {
        var h = new ProxyHarness(Duration.ZERO);
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(redirect(302, "/books/OL1M.json"));             // 1,2
        h.results.add(json(200, book("{\"title\":\"t\",\"covers\":[9273767],\"authors\":[{\"key\":\"/authors/OL1A\"}],\"works\":[{\"key\":\"/works/OL1W\"}]}"))); // 3 pinned SOCKS
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false);
        h.results.add(json(200, "{\"docs\":[{\"key\":\"/works/OL1W\",\"author_key\":[\"OL1A\"],\"author_name\":[\"甲\"]}]}")); // 4,5
        h.fail(OpenLibraryClient.FailureReason.CONNECT, false); h.results.add(redirect(302, ARCHIVE_URL));                     // 6,7
        h.results.add(new OpenLibraryClient.Reply(200, "image/png", null, null, BookCoverApiTest.image("png", 4, 6)));        // 8 pinned
        var result = h.client.lookup(ISBN);
        assertThat(result.fields().author()).isEqualTo("甲"); assertThat(result.cover()).isNotNull();
        assertThat(h.routes).hasSize(8);
        assertThat(result.warnings()).extracting(w -> w.code()).containsExactly("DESCRIPTION_SKIPPED");
    }

    @Test
    void apiNeverBuildsLegacyOrArbitraryUpstreamUrls() {
        assertThat(OpenLibraryClient.permittedMetadataResource(URI.create("https://openlibrary.org/isbn/" + ISBN + ".json"))).isTrue();
        assertThat(OpenLibraryClient.permittedMetadataResource(URI.create("https://openlibrary.org/books/OL7353617M.json"))).isTrue();
        assertThat(OpenLibraryClient.permittedMetadataResource(URI.create("https://openlibrary.org/works/OL45804W.json"))).isTrue();
        assertThat(OpenLibraryClient.permittedMetadataResource(URI.create(
                "https://openlibrary.org/search.json?q=isbn:" + ISBN + "&fields=key,author_key,author_name&limit=1"))).isTrue();
        for (String url : List.of("https://openlibrary.org/api/books?bibkeys=ISBN:" + ISBN + "&jscmd=data&format=json",
                "https://openlibrary.org/search.json?q=isbn:" + ISBN + "&fields=*&limit=1", "https://openlibrary.org/search.json?q=title:x&fields=key,author_key,author_name&limit=1",
                "https://openlibrary.org/search.json?q=isbn:" + ISBN + "&fields=key,author_key,author_name&limit=100",
                "https://openlibrary.org/authors/OL1A.json", "https://openlibrary.org/works/OL1W/editions.json", "https://openlibrary.org/books/OL1M.json?x=1",
                "https://openlibrary.org/isbn/123.json", "https://openlibrary.org/isbn/%30.json", "http://openlibrary.org/isbn/" + ISBN + ".json",
                "https://openlibrary.org:8443/isbn/" + ISBN + ".json", "https://u@openlibrary.org/isbn/" + ISBN + ".json", "https://evil.example/isbn/" + ISBN + ".json")) {
            assertThat(OpenLibraryClient.permittedMetadataResource(URI.create(url))).as(url).isFalse();
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
