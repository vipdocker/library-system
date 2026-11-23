package com.example.library.catalog;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.proxy.ProxySnapshot;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenLibraryRequestPolicyTest {
    private static final long S = TimeUnit.SECONDS.toNanos(1);
    private static final URI META = URI.create("https://openlibrary.org/isbn/080442957X.json");
    private static final URI COVER_ISBN = URI.create("https://covers.openlibrary.org/b/isbn/080442957X-M.jpg?default=false");
    private static final URI COVER_ID = URI.create("https://covers.openlibrary.org/b/id/9273767-M.jpg?default=false");
    private static final ProxySnapshot PROXY = new ProxySnapshot(true, "localhost", 9999, 4, null);

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T00:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private final AtomicLong time = new AtomicLong(Long.MAX_VALUE - 5 * S); // monotonic ticker close to wrap-around
    private final MutableClock clock = new MutableClock();
    private final OpenLibraryRequestPolicy policy = new OpenLibraryRequestPolicy(time::get, clock, Duration.ofSeconds(30));

    private void reserve(URI uri) throws InterruptedException { policy.reserve(uri, time.get() + 14 * S, time::addAndGet); }

    private static Integer retry(ApiException e) { return (Integer) e.getDetails().get("retryAfterSeconds"); }

    @Test
    void retryAfterParsesDecimalSecondsHttpDateAndRejectsInvalid() {
        Instant now = clock.instant();
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter("0", now).nanos()).isZero();
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter(" 301 ", now).nanos()).isEqualTo(301 * S);
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter("3600", now).nanos()).isEqualTo(3600 * S);
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter("1.2", now).nanos()).isEqualTo(2 * S);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(120).atOffset(ZoneOffset.UTC));
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter(date, now).nanos()).isEqualTo(120 * S);
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(120).atOffset(ZoneOffset.UTC));
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter(past, now).nanos()).isZero();
        assertThat(OpenLibraryRequestPolicy.parseRetryAfter("99999999999999999999999", now).huge()).isTrue();
        for (String bad : new String[]{null, "", " ", "bad", "-1", "1e3", "Wed, 21 Oct 2026", "+5", "0x10", "5 s"}) {
            assertThat(OpenLibraryRequestPolicy.parseRetryAfter(bad, now)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void upstream429CooldownRespectsFullValidValuesWithoutTruncation() throws Exception {
        for (String raw : new String[]{"301", "3600"}) {
            var p = new OpenLibraryRequestPolicy(time::get, clock, Duration.ofSeconds(30));
            var limited = p.onResponse(META, 429, raw);
            assertThat(limited.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED);
            assertThat(retry(limited)).isEqualTo(Integer.parseInt(raw));
            time.addAndGet((Integer.parseInt(raw) - 1) * S);
            assertThatThrownBy(() -> p.reserve(META, time.get() + 14 * S, time::addAndGet)).isInstanceOfSatisfying(ApiException.class,
                    e -> { assertThat(e.getDetails()).containsEntry("reason", "COOLDOWN"); assertThat(retry(e)).isEqualTo(1); });
            time.addAndGet(S);
            p.reserve(META, time.get() + 14 * S, time::addAndGet);
        }
    }

    @Test
    void missingOrInvalidRetryAfterOn429IsSixtySecondsAndZeroKeepsOneSecondFloor() throws Exception {
        for (String raw : new String[]{null, "bad", "Wed, 21 Oct 2026"}) {
            var p = new OpenLibraryRequestPolicy(time::get, clock, Duration.ofSeconds(30));
            assertThat(retry(p.onResponse(META, 429, raw))).isEqualTo(60);
        }
        var zero = new OpenLibraryRequestPolicy(time::get, clock, Duration.ofSeconds(30));
        assertThat(retry(zero.onResponse(META, 429, "0"))).isEqualTo(1);
    }

    @Test
    void hugeRetryAfterBlocksConservativelyWithNullSecondsAndNoOverflow() throws Exception {
        var limited = policy.onResponse(META, 429, "9".repeat(40));
        assertThat(limited.getDetails()).doesNotContainKey("retryAfterSeconds").containsEntry("longWait", true);
        time.addAndGet(TimeUnit.DAYS.toNanos(365L * 50)); // wraps the raw ticker value; differences stay correct
        assertThatThrownBy(() -> reserve(META)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "COOLDOWN").containsEntry("longWait", true).doesNotContainKey("retryAfterSeconds"));
        var big = new OpenLibraryRequestPolicy(time::get, clock, Duration.ofSeconds(30));
        assertThat(big.onResponse(META, 429, "2147483648").getDetails()).containsEntry("longWait", true).doesNotContainKey("retryAfterSeconds");
    }

    @Test
    void httpDateIsConvertedOnceSoLaterWallClockChangesNeverShortenCooldown() throws Exception {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock.instant().plusSeconds(600).atOffset(ZoneOffset.UTC));
        assertThat(retry(policy.onResponse(META, 429, date))).isEqualTo(600);
        clock.now.set(clock.instant().plus(Duration.ofDays(2))); // wall clock jumps forward
        time.addAndGet(10 * S);
        assertThatThrownBy(() -> reserve(META)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(retry(e)).isEqualTo(590));
    }

    @Test
    void otherErrorStatusesRespectValidRetryAfterButDoNotInventCooldown() throws Exception {
        assertThat(policy.onResponse(META, 503, null)).isNull();
        reserve(META);
        assertThat(policy.onResponse(META, 503, "120")).isNull();
        time.addAndGet(5 * S);
        assertThatThrownBy(() -> reserve(META)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(retry(e)).isEqualTo(115));
    }

    @Test
    void coversIsbn403WithoutHeaderCoolsOnlyIsbnCoversForSixtySecondsAndOther403AreNotRateLimits() throws Exception {
        assertThat(policy.onResponse(META, 403, null)).isNull();
        assertThat(policy.onResponse(COVER_ID, 403, null)).isNull();
        reserve(META);
        var rejected = policy.onResponse(COVER_ISBN, 403, null);
        assertThat(rejected.getDetails()).containsEntry("reason", "COVER_REJECTED"); assertThat(retry(rejected)).isEqualTo(60);
        time.addAndGet(S);
        assertThatThrownBy(() -> reserve(COVER_ISBN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "COVER_COOLDOWN"));
        reserve(META); time.addAndGet(S); reserve(COVER_ID);
        time.addAndGet(60 * S); reserve(COVER_ISBN);
    }

    @Test
    void sharedOneRequestPerSecondAndSixtyPerFiveMinuteWindowCountEveryReservation() throws Exception {
        long start = time.get();
        for (int i = 0; i < 60; i++) { reserve(i % 2 == 0 ? META : COVER_ID); }
        assertThat(time.get() - start).isEqualTo(59 * S);
        assertThatThrownBy(() -> reserve(META)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getDetails()).containsEntry("reason", "WINDOW_LIMIT"));
        time.addAndGet(TimeUnit.MINUTES.toNanos(5)); reserve(META);
    }

    @Test
    void enormousRetryAfterAndRedirectRetryAfterNeverEndCooldownEarly() throws Exception {
        assertThat(policy.onResponse(META, 429, "9".repeat(300)).getDetails()).containsEntry("longWait", true);
        var redirectPolicy = new OpenLibraryRequestPolicy(time::get, clock, Duration.ZERO);
        assertThat(redirectPolicy.onResponse(META, 302, "301")).isNull();
        assertThatThrownBy(() -> redirectPolicy.reserve(META, time.get() + 14 * S, time::addAndGet))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(retry(e)).isEqualTo(301));
    }

    @Test
    void directFailureMemoryIsShortLivedPerHostAndRevisionWithSingleHalfOpenProbe() {
        String host = "openlibrary.org";
        assertThat(policy.directState(host, PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.COLD);
        policy.learnDirectFailure(host, PROXY);
        assertThat(policy.directState(host, PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.SKIP);
        assertThat(policy.directState("covers.openlibrary.org", PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.COLD);
        assertThat(policy.directState(host, new ProxySnapshot(true, "localhost", 9999, 5, null))).isEqualTo(OpenLibraryRequestPolicy.DirectState.COLD);
        assertThat(policy.directState(host, new ProxySnapshot(false, null, null, 4, null))).isEqualTo(OpenLibraryRequestPolicy.DirectState.COLD);
        time.addAndGet(29 * S);
        assertThat(policy.directState(host, PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.SKIP);
        time.addAndGet(S);
        assertThat(policy.directState(host, PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.HALF_OPEN);
        policy.clearDirectFailure(host);
        assertThat(policy.directState(host, PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.COLD);
        var off = new OpenLibraryRequestPolicy(time::get, clock, Duration.ZERO);
        off.learnDirectFailure(host, PROXY);
        assertThat(off.directState(host, PROXY)).isEqualTo(OpenLibraryRequestPolicy.DirectState.COLD);
        assertThatThrownBy(() -> new OpenLibraryRequestPolicy(time::get, clock, Duration.ofSeconds(61))).isInstanceOf(IllegalArgumentException.class);
    }
}
