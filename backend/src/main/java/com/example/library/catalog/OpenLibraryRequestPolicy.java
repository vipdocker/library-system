package com.example.library.catalog;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.proxy.ProxySnapshot;

/**
 * Shared upstream request policy (OL-OPT-v1): one request per second and 60 per rolling five minutes for every real
 * outbound attempt (fallbacks, redirects and cover hops included; cache hits never reach here), Retry-After cooldowns
 * converted once into monotonic deadlines, and a short per-host memory of DIRECT network failures.
 * All deadlines use the monotonic ticker with subtraction comparisons, so wall-clock changes and ticker wrap-around
 * cannot end a cooldown early.
 */
final class OpenLibraryRequestPolicy {
    static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long WINDOW = TimeUnit.MINUTES.toNanos(5);
    private static final int WINDOW_LIMIT = 60;
    private static final long DEFAULT_429 = 60 * SECOND;
    private static final long COVER_REJECTED = 60 * SECOND;
    /** Saturation bound for absurd Retry-After values: ~250 years, still below the 2^63 ns signed-difference limit. */
    static final long MAX_BLOCK = TimeUnit.DAYS.toNanos(365L * 250);
    private static final long MAX_TTL = 60 * SECOND;
    private static final Pattern DECIMAL = Pattern.compile("([0-9]+)(?:\\.([0-9]+))?");

    enum DirectState { COLD, SKIP, HALF_OPEN }
    record Delay(long nanos, boolean huge) { }
    private record DirectMemo(long revision, long expiresAt) { }

    private final LongSupplier ticker;
    private final Clock clock;
    private final long directFailureTtl;
    private final ArrayDeque<Long> exchanges = new ArrayDeque<>();
    private long cooldownUntil;
    private boolean coolingDown;
    private long coversIsbnUntil;
    private boolean coversIsbnCooling;
    private final Map<String, DirectMemo> directFailures = new HashMap<>();

    OpenLibraryRequestPolicy(LongSupplier ticker, Clock clock, Duration directFailureTtl) {
        if (directFailureTtl == null || directFailureTtl.isNegative() || directFailureTtl.toNanos() > MAX_TTL) {
            throw new IllegalArgumentException("direct failure TTL must be within 0..60s");
        }
        this.ticker = ticker;
        this.clock = clock;
        this.directFailureTtl = directFailureTtl.toNanos();
    }

    /** Reserves one real outbound attempt or throws; waits at most for the 1 s spacing and only inside the query deadline. */
    synchronized void reserve(URI target, long deadline, OpenLibraryClient.Sleeper sleeper) throws InterruptedException {
        long now = ticker.getAsLong();
        if (deadline - now <= 0) { throw unavailable("TIMEOUT"); }
        if (coolingDown && cooldownUntil - now > 0) { throw rateLimited(cooldownUntil - now, "COOLDOWN"); }
        coolingDown = false;
        if (isCoversIsbn(target) && coversIsbnCooling && coversIsbnUntil - now > 0) {
            throw rateLimited(coversIsbnUntil - now, "COVER_COOLDOWN");
        }
        while (!exchanges.isEmpty() && now - exchanges.getFirst() >= WINDOW) { exchanges.removeFirst(); }
        if (exchanges.size() >= WINDOW_LIMIT) {
            throw rateLimited(WINDOW - (now - exchanges.getFirst()), "WINDOW_LIMIT");
        }
        if (!exchanges.isEmpty()) {
            long wait = SECOND - (now - exchanges.getLast());
            if (wait > 0) {
                if (deadline - now <= wait) { throw unavailable("TIMEOUT"); }
                sleeper.sleep(wait);
                now = ticker.getAsLong();
            }
        }
        if (deadline - now < 1_000_000) { throw unavailable("TIMEOUT"); }
        exchanges.addLast(now); // every real outbound attempt counts, failed ones included
    }

    /**
     * Applies upstream throttling signals. Returns the rate-limit exception to surface for 429 and for covers-by-ISBN 403
     * (documented as the covers rate limit, labelled only as a possible limit); other statuses never become rate limits,
     * but a valid Retry-After on any 4xx/5xx is still respected as a shared cooldown.
     */
    synchronized ApiException onResponse(URI uri, int status, String retryAfter) {
        long now = ticker.getAsLong();
        Delay delay = parseRetryAfter(retryAfter, clock.instant());
        if (status == 403 && isCoversIsbn(uri)) {
            long wait = delay == null ? COVER_REJECTED : Math.max(SECOND, delay.nanos());
            coversIsbnUntil = now + wait; coversIsbnCooling = true;
            return rateLimited(wait, "COVER_REJECTED");
        }
        if (status == 429) {
            long wait = delay == null ? DEFAULT_429 : Math.max(SECOND, delay.nanos());
            extendCooldown(now, wait);
            return rateLimited(wait, "UPSTREAM");
        }
        if (delay != null && delay.nanos() > 0) { extendCooldown(now, delay.nanos()); }
        return null;
    }

    private void extendCooldown(long now, long wait) {
        if (!coolingDown || cooldownUntil - now < wait) { cooldownUntil = now + wait; coolingDown = true; }
    }

    /** Non-negative decimal seconds (fractions rounded up) or an RFC 1123 HTTP-date, converted once; null when invalid. */
    static Delay parseRetryAfter(String raw, Instant now) {
        if (raw == null) { return null; }
        String value = raw.trim();
        if (value.isEmpty()) { return null; }
        var decimal = DECIMAL.matcher(value);
        if (decimal.matches()) {
            String whole = decimal.group(1).replaceFirst("^0+(?=.)", "");
            boolean fraction = decimal.group(2) != null && !decimal.group(2).matches("0+");
            if (whole.length() > 12) { return new Delay(MAX_BLOCK, true); }
            long seconds = Long.parseLong(whole) + (fraction ? 1 : 0);
            return seconds > MAX_BLOCK / SECOND ? new Delay(MAX_BLOCK, true) : new Delay(seconds * SECOND, false);
        }
        try {
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (!date.isAfter(now)) { return new Delay(0, false); }
            Duration until = Duration.between(now, date);
            long seconds = until.getSeconds() + (until.getNano() > 0 ? 1 : 0);
            return seconds > MAX_BLOCK / SECOND ? new Delay(MAX_BLOCK, true) : new Delay(seconds * SECOND, false);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    synchronized DirectState directState(String host, ProxySnapshot snapshot) {
        if (!snapshot.enabled() || directFailureTtl == 0) { return DirectState.COLD; }
        DirectMemo memo = directFailures.get(host);
        if (memo == null || memo.revision() != snapshot.revision()) { return DirectState.COLD; }
        return memo.expiresAt() - ticker.getAsLong() > 0 ? DirectState.SKIP : DirectState.HALF_OPEN;
    }

    /** Called only after a DIRECT network failure was followed by a SOCKS attempt that served a validated resource. */
    synchronized void learnDirectFailure(String host, ProxySnapshot snapshot) {
        if (directFailureTtl == 0 || !snapshot.enabled()) { return; }
        directFailures.put(host, new DirectMemo(snapshot.revision(), ticker.getAsLong() + directFailureTtl));
    }

    synchronized void clearDirectFailure(String host) { directFailures.remove(host); }

    static boolean isCoversIsbn(URI uri) {
        return uri != null && "covers.openlibrary.org".equals(uri.getHost()) && uri.getRawPath() != null && uri.getRawPath().startsWith("/b/isbn/");
    }

    static ApiException rateLimited(long nanos, String reason) {
        var details = new LinkedHashMap<String, Object>();
        long seconds = Math.max(1, nanos / SECOND + (nanos % SECOND > 0 ? 1 : 0));
        if (seconds <= Integer.MAX_VALUE) { details.put("retryAfterSeconds", (int) seconds); }
        else { details.put("longWait", true); }
        details.put("reason", reason);
        return new ApiException(ErrorCode.OPEN_LIBRARY_RATE_LIMITED, ErrorCode.OPEN_LIBRARY_RATE_LIMITED.getDefaultMessage(), details, List.of());
    }

    private static ApiException unavailable(String reason) {
        return new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(),
                Map.of("reason", reason), List.of());
    }
}
