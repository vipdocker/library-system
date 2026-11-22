package com.example.library.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import com.example.library.catalog.dto.OpenLibraryLookupResponse;
import com.example.library.catalog.dto.OpenLibraryLookupResponse.Cover;
import com.example.library.catalog.dto.OpenLibraryLookupResponse.Fields;
import com.example.library.catalog.dto.OpenLibraryLookupResponse.Warning;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.util.IsbnUtils;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed upstream only; one query, no queued work/retries, bounded bytes and deadlines including body reads. */
@Component
public class OpenLibraryClient {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long WINDOW = TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_METADATA = 256 * 1024;
    private static final Pattern COVER_PATH = Pattern.compile("/b/id/([1-9][0-9]{0,18})-[SML]\\.(?:jpg|jpeg|png)");
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{L}\\p{N}])([1-9][0-9]{3})(?![\\p{L}\\p{N}])");
    private final Transport transport;
    private final com.example.library.common.proxy.ProxySnapshotProvider proxySettings;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final LongSupplier ticker;
    private final Sleeper sleeper;
    private final CoverImageValidator validator;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Semaphore permit = new Semaphore(1);
    private final ArrayDeque<Long> exchanges = new ArrayDeque<>();
    private long cooldownUntil;
    private boolean coolingDown;

    @Autowired
    public OpenLibraryClient(CoverImageValidator validator, com.example.library.common.proxy.ProxySnapshotProvider proxySettings) {
        this(new ReactorNettyTransport(), System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), validator, proxySettings);
    }

    OpenLibraryClient(Transport transport, LongSupplier ticker, Sleeper sleeper, CoverImageValidator validator) {
        this(transport, ticker, sleeper, validator, () -> new com.example.library.common.proxy.ProxySnapshot(false, null, null, 0, null));
    }

    OpenLibraryClient(Transport transport, LongSupplier ticker, Sleeper sleeper, CoverImageValidator validator,
                      com.example.library.common.proxy.ProxySnapshotProvider proxySettings) {
        this.transport = transport;
        this.proxySettings = proxySettings;
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.validator = validator;
    }

    @FunctionalInterface
    interface Transport extends AutoCloseable {
        Reply get(URI uri, int maxBytes, Duration timeout) throws IOException, InterruptedException;
        default Reply get(URI uri, Route route, int maxBytes, Duration timeout) throws IOException, InterruptedException {
            if (route.kind() != RouteKind.DIRECT) { throw new TransportFailure(FailureReason.PROTOCOL, false); }
            return get(uri, maxBytes, timeout);
        }
        default CompletionStage<Void> quiescence() { return CompletableFuture.completedFuture(null); }
        @Override default void close() { }
    }
    @FunctionalInterface
    interface RoutedTransport extends Transport {
        @Override Reply get(URI uri, Route route, int maxBytes, Duration timeout) throws IOException, InterruptedException;
        @Override default Reply get(URI uri, int maxBytes, Duration timeout) throws IOException, InterruptedException {
            return get(uri, Route.direct(), maxBytes, timeout);
        }
    }
    enum RouteKind { DIRECT, SOCKS5 }
    record Route(RouteKind kind, String host, Integer port) {
        static Route direct() { return new Route(RouteKind.DIRECT, null, null); }
        static Route socks(com.example.library.common.proxy.ProxySnapshot settings) {
            return new Route(RouteKind.SOCKS5, settings.host(), settings.port());
        }
    }
    enum FailureReason { DNS, CONNECT, NO_ROUTE, RESET, TIMEOUT, TLS, PROTOCOL, CANCELLED, BODY_TOO_LARGE, NETWORK }
    static final class TransportFailure extends IOException {
        final FailureReason reason;
        final boolean headersReceived;
        TransportFailure(FailureReason reason, boolean headersReceived) {
            super(reason.name()); this.reason = reason; this.headersReceived = headersReceived;
        }
        boolean fallbackAllowed() {
            return !headersReceived && switch (reason) {
                case DNS, CONNECT, NO_ROUTE, RESET, TIMEOUT -> true;
                default -> false;
            };
        }
    }
    @FunctionalInterface
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    record Reply(int status, String mediaType, String retryAfter, byte[] body) { }

    public OpenLibraryLookupResponse lookup(String isbn) {
        if (!IsbnUtils.isValid(isbn) || !isbn.equals(IsbnUtils.normalize(isbn))) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "ISBN 必须规范化且校验通过");
        }
        if (closed.get()) { throw unavailable("CANCELLED"); }
        if (!permit.tryAcquire()) {
            throw rateLimited(1, "BUSY");
        }
        long deadline = ticker.getAsLong() + 14 * SECOND;
        try {
            var query = new QueryContext(proxySettings.snapshot());
            Reply metadata = exchange(URI.create("https://openlibrary.org/api/books?bibkeys=ISBN:" + isbn
                    + "&jscmd=data&format=json"), MAX_METADATA, deadline, query);
            if (metadata.status() == 429) {
                throw serverRateLimit(metadata.retryAfter());
            }
            if (metadata.status() == 404) {
                return response(isbn, false, new Fields(null, null, null, null, null), null, List.of(), null);
            }
            if (metadata.status() != 200) {
                throw query.failure("HTTP_ERROR");
            }
            JsonNode root;
            try {
                root = mapper.readTree(metadata.body());
            } catch (JacksonException ex) {
                throw query.failure("INVALID_JSON");
            }
            if (root == null || !root.isObject()) {
                throw query.failure("INVALID_JSON");
            }
            JsonNode book = root.get("ISBN:" + isbn);
            if (book == null || book.isNull()) {
                return response(isbn, false, new Fields(null, null, null, null, null), null, List.of(), null);
            }
            if (!book.isObject()) {
                throw query.failure("INVALID_JSON");
            }
            List<Warning> warnings = new ArrayList<>();
            Fields fields = new Fields(text(book.get("title"), "title", 200, warnings),
                    names(book.get("authors"), "author", 100, warnings),
                    names(book.get("publishers"), "publisher", 100, warnings),
                    text(book.get("description"), "description", 2000, warnings),
                    year(book.get("publish_date"), warnings));
            URI coverUri = coverUri(book.get("cover"), isbn, warnings);
            Cover cover = null;
            Integer retry = null;
            if (coverUri != null) {
                try {
                    query.coverStage();
                    Reply image = exchange(coverUri, CoverImageValidator.MAX_BYTES, deadline, query);
                    if (image.status() == 429) {
                        throw serverRateLimit(image.retryAfter());
                    }
                    if (image.status() == 200) {
                        String type = image.mediaType() == null ? "" : image.mediaType().split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                        var valid = validator.validate(type, image.body());
                        if (deadline - ticker.getAsLong() <= 0) { throw query.failure("TIMEOUT"); }
                        cover = new Cover(valid.mediaType(), Base64.getEncoder().encodeToString(valid.bytes()),
                                valid.bytes().length, valid.width(), valid.height());
                    } else if (image.status() != 404) {
                        warn(warnings, "COVER_UNAVAILABLE", "cover", "封面获取失败，书目信息仍可应用");
                    }
                } catch (ApiException ex) {
                    if (ex.getCode() == ErrorCode.OPEN_LIBRARY_RATE_LIMITED) {
                        retry = (Integer) ex.getDetails().get("retryAfterSeconds");
                    }
                    warn(warnings, ex.getCode().name(), "cover", "封面获取或校验失败，书目信息仍可应用");
                }
            }
            return response(isbn, true, fields, cover, warnings, retry);
        } finally {
            transport.quiescence().whenComplete((ignored, error) -> permit.release());
        }
    }

    private static final class QueryContext {
        final com.example.library.common.proxy.ProxySnapshot settings;
        String stage = "METADATA";
        RouteKind lastRoute = RouteKind.DIRECT;
        String directReason;
        QueryContext(com.example.library.common.proxy.ProxySnapshot settings) { this.settings = settings; }
        void coverStage() { stage = "COVER"; lastRoute = RouteKind.DIRECT; directReason = null; }
        ApiException failure(String reason) {
            var details = new java.util.LinkedHashMap<String, Object>();
            details.put("reason", reason); details.put("stage", stage); details.put("lastRoute", lastRoute.name());
            details.put("proxyOutcome", !settings.enabled() ? "DISABLED" : lastRoute == RouteKind.SOCKS5 ? "FAILED" : "NOT_ATTEMPTED");
            if (directReason != null) { details.put("directReason", directReason); }
            if (lastRoute == RouteKind.SOCKS5) { details.put("proxyReason", reason); }
            return new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(), details, List.of());
        }
    }

    private Reply exchange(URI uri, int maxBytes, long deadline, QueryContext query) {
        try {
            return attempt(uri, Route.direct(), maxBytes, deadline, query);
        } catch (TransportFailure direct) {
            query.directReason = direct.reason.name();
            if (!query.settings.enabled() || !direct.fallbackAllowed() || deadline - ticker.getAsLong() < 1_000_000
                    || Thread.currentThread().isInterrupted() || closed.get() || !transport.quiescence().toCompletableFuture().isDone()) {
                throw query.failure(Thread.currentThread().isInterrupted() || closed.get() ? "CANCELLED" : direct.reason.name());
            }
            try { return attempt(uri, Route.socks(query.settings), maxBytes, deadline, query); }
            catch (TransportFailure proxy) { throw query.failure(proxy.reason.name()); }
        }
    }

    private Reply attempt(URI uri, Route route, int maxBytes, long deadline, QueryContext query) throws TransportFailure {
        try {
            if (Thread.currentThread().isInterrupted() || closed.get()) { throw query.failure("CANCELLED"); }
            if (!transport.quiescence().toCompletableFuture().isDone()) { throw query.failure("TIMEOUT"); }
            if (deadline - ticker.getAsLong() < 1_000_000) { throw query.failure("TIMEOUT"); }
            reserveExchange(deadline);
            long remaining = deadline - ticker.getAsLong();
            if (remaining < 1_000_000) { throw query.failure("TIMEOUT"); }
            query.lastRoute = route.kind();
            Reply reply = transport.get(uri, route, maxBytes, Duration.ofNanos(Math.min(6 * SECOND, remaining)));
            if (deadline - ticker.getAsLong() <= 0) { throw query.failure("TIMEOUT"); }
            if (reply.body().length > maxBytes) { throw query.failure("BODY_TOO_LARGE"); }
            return reply;
        } catch (TransportFailure ex) {
            throw ex;
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCode.OPEN_LIBRARY_UNAVAILABLE) { throw query.failure((String) ex.getDetails().get("reason")); }
            throw ex;
        } catch (javax.net.ssl.SSLException ex) {
            throw query.failure("TLS");
        } catch (HttpTimeoutException ex) {
            throw query.failure("TIMEOUT");
        } catch (IOException ex) {
            throw query.failure("NETWORK");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw query.failure("CANCELLED");
        }
    }

    private void reserveExchange(long deadline) throws InterruptedException {
        long now = ticker.getAsLong();
        ensureTime(deadline);
        if (coolingDown && cooldownUntil - now > 0) {
            throw rateLimited(seconds(cooldownUntil - now), "COOLDOWN");
        }
        coolingDown = false;
        while (!exchanges.isEmpty() && now - exchanges.getFirst() >= WINDOW) { exchanges.removeFirst(); }
        if (exchanges.size() >= 60) {
            throw rateLimited(seconds(WINDOW - (now - exchanges.getFirst())), "WINDOW_LIMIT");
        }
        if (!exchanges.isEmpty()) {
            long wait = SECOND - (now - exchanges.getLast());
            if (wait > 0) {
                if (deadline - now <= wait) { throw unavailable("TIMEOUT"); }
                sleeper.sleep(wait);
                now = ticker.getAsLong();
                ensureTime(deadline);
            }
        }
        if (deadline - now < 1_000_000) { throw unavailable("TIMEOUT"); }
        exchanges.addLast(now); // Every real outbound attempt counts, including failed ones.
    }

    private ApiException serverRateLimit(String raw) {
        int retry = 60;
        if (raw != null && raw.trim().matches("[0-9]{1,3}")) {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed >= 1 && parsed <= 300) { retry = parsed; }
        }
        cooldownUntil = ticker.getAsLong() + retry * SECOND;
        coolingDown = true;
        return rateLimited(retry, "UPSTREAM");
    }

    private static int seconds(long nanos) {
        return (int) Math.max(1, Math.min(300, (nanos + SECOND - 1) / SECOND));
    }
    private void ensureTime(long deadline) {
        if (deadline - ticker.getAsLong() <= 0) { throw unavailable("TIMEOUT"); }
    }
    private static ApiException rateLimited(int retry, String reason) {
        return new ApiException(ErrorCode.OPEN_LIBRARY_RATE_LIMITED, ErrorCode.OPEN_LIBRARY_RATE_LIMITED.getDefaultMessage(),
                Map.of("retryAfterSeconds", retry, "reason", reason), List.of());
    }
    private static ApiException unavailable(String reason) {
        return new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(),
                Map.of("reason", reason), List.of());
    }

    private static OpenLibraryLookupResponse response(String isbn, boolean found, Fields fields, Cover cover,
                                                       List<Warning> warnings, Integer retry) {
        return new OpenLibraryLookupResponse(isbn, found, "https://openlibrary.org/isbn/" + isbn,
                fields, cover, List.copyOf(warnings), retry);
    }
    private static void warn(List<Warning> warnings, String code, String field, String message) {
        warnings.add(new Warning(code, field, message));
    }
    private static String text(JsonNode value, String field, int max, List<Warning> warnings) {
        if (value == null || value.isNull()) { return null; }
        if (!value.isTextual()) {
            warn(warnings, "FIELD_INVALID", field, "上游字段类型不合法，保留人工录入"); return null;
        }
        String result = value.asText().trim();
        if (result.length() > max) {
            warn(warnings, "FIELD_TOO_LONG", field, "上游字段过长，未截断或应用"); return null;
        }
        return result.isEmpty() ? null : result;
    }
    private static String names(JsonNode values, String field, int max, List<Warning> warnings) {
        if (values == null || values.isNull()) { return null; }
        if (!values.isArray()) {
            warn(warnings, "FIELD_INVALID", field, "上游姓名列表类型不合法"); return null;
        }
        var names = new LinkedHashSet<String>();
        for (JsonNode item : values) {
            JsonNode name = item.isObject() ? item.get("name") : null;
            if (!item.isObject() || name != null && !name.isNull() && !name.isTextual()) {
                warn(warnings, "FIELD_INVALID", field, "上游姓名类型不合法"); return null;
            }
            if (name != null && name.isTextual() && !name.asText().trim().isEmpty()) { names.add(name.asText().trim()); }
        }
        String joined = String.join("、", names);
        if (joined.length() > max) {
            warn(warnings, "FIELD_TOO_LONG", field, "上游姓名列表过长，未截断或应用"); return null;
        }
        return joined.isEmpty() ? null : joined;
    }
    private static Integer year(JsonNode value, List<Warning> warnings) {
        String date = text(value, "publishYear", MAX_METADATA, warnings);
        if (date == null) { return null; }
        var matcher = YEAR.matcher(date);
        if (!matcher.find()) {
            warn(warnings, "YEAR_AMBIGUOUS", "publishYear", "出版日期没有唯一四位年份"); return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        if (matcher.find()) {
            warn(warnings, "YEAR_AMBIGUOUS", "publishYear", "出版日期含多个年份"); return null;
        }
        return year;
    }
    private static URI coverUri(JsonNode cover, String isbn, List<Warning> warnings) {
        if (cover == null || cover.isNull()) {
            return URI.create("https://covers.openlibrary.org/b/isbn/" + isbn + "-M.jpg?default=false");
        }
        if (cover.isObject()) {
            JsonNode reference = null;
            for (String size : List.of("medium", "large", "small")) {
                JsonNode candidate = cover.get(size);
                if (candidate != null && !candidate.isNull()) { reference = candidate; break; }
            }
            if (reference == null) { return URI.create("https://covers.openlibrary.org/b/isbn/" + isbn + "-M.jpg?default=false"); }
            if (reference.isTextual()) {
                try {
                    URI uri = URI.create(reference.asText());
                    var path = COVER_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
                    if ("https".equals(uri.getScheme()) && "covers.openlibrary.org".equals(uri.getHost())
                            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null
                            && uri.getRawFragment() == null && path.matches() && Long.parseLong(path.group(1)) > 0) {
                        return URI.create("https://covers.openlibrary.org/b/id/" + path.group(1) + "-M.jpg?default=false");
                    }
                } catch (IllegalArgumentException ignored) { /* Never forward arbitrary upstream URLs. */ }
            }
        }
        warn(warnings, "COVER_REFERENCE_REJECTED", "cover", "上游封面引用不在许可范围，未发起请求");
        return null;
    }

    @PreDestroy
    public void close() { if (closed.compareAndSet(false, true)) { transport.close(); } }

}
