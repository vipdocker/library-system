package com.example.library.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import com.example.library.catalog.dto.ShowApiLookupResponse;
import com.example.library.catalog.dto.ShowApiLookupResponse.Additional;
import com.example.library.catalog.dto.ShowApiLookupResponse.Capabilities;
import com.example.library.catalog.dto.ShowApiLookupResponse.Cover;
import com.example.library.catalog.dto.ShowApiLookupResponse.Fields;
import com.example.library.catalog.dto.ShowApiLookupResponse.Warning;
import com.example.library.catalog.settings.ShowApiSettings;
import com.example.library.catalog.settings.ShowApiSettingsProvider;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.proxy.ProxySnapshot;
import com.example.library.common.proxy.ProxySnapshotProvider;
import com.example.library.common.util.IsbnUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ShowAPI ISBN client (showapi-design-v1 §2/§4/§6): fixed upstream only, one query at a time with
 * no queue, metadata at most once per second, bounded bytes and deadlines, no implicit retries or
 * redirects. Only proven-unsent DNS/connect failures may fall back to the admin SOCKS proxy once.
 * The AppKey is injected into the metadata URL by the backend and never appears in responses,
 * errors or logs; cover requests carry no credentials at all.
 */
@Component
public class ShowApiClient {
    private static final Logger log = LoggerFactory.getLogger(ShowApiClient.class);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_METADATA = 256 * 1024;
    public static final String SOURCE_URL = "https://www.showapi.com/apis/isbn-book-1626";
    private static final Pattern COVER_PATH =
            Pattern.compile("/app2/isbn/(?:imgs/[A-Za-z0-9][A-Za-z0-9._-]*|[0-9]{1,6}/[a-f0-9]{32})\\.(?:jpg|jpeg|png)");
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{L}\\p{N}])([1-9][0-9]{3})(?![\\p{L}\\p{N}])");

    private final Transport transport;
    private final ProxySnapshotProvider proxySettings;
    private final ShowApiSettingsProvider config;
    private final LongSupplier ticker;
    private final Sleeper sleeper;
    private final CoverImageValidator validator;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Semaphore permit = new Semaphore(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private long lastMetadata;
    private long cooldownUntil;
    private boolean coolingDown;

    @Autowired
    public ShowApiClient(CoverImageValidator validator, ProxySnapshotProvider proxySettings, ShowApiSettingsProvider config) {
        this(new ShowApiTransport(), System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos),
                validator, proxySettings, config);
    }

    ShowApiClient(Transport transport, LongSupplier ticker, Sleeper sleeper, CoverImageValidator validator,
                  ProxySnapshotProvider proxySettings, ShowApiSettingsProvider config) {
        this.transport = transport;
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.validator = validator;
        this.proxySettings = proxySettings;
        this.config = config;
    }

    @FunctionalInterface
    interface Transport extends AutoCloseable {
        Reply exchange(URI uri, String method, byte[] body, Route route, int maxBytes, Duration timeout)
                throws IOException, InterruptedException;
        default CompletionStage<Void> quiescence() { return CompletableFuture.completedFuture(null); }
        @Override default void close() { }
    }
    enum RouteKind { DIRECT, SOCKS5 }
    record Route(RouteKind kind, String host, Integer port) {
        static Route direct() { return new Route(RouteKind.DIRECT, null, null); }
        static Route socks(ProxySnapshot settings) { return new Route(RouteKind.SOCKS5, settings.host(), settings.port()); }
    }
    enum FailureReason { DNS, CONNECT, NO_ROUTE, RESET, TIMEOUT, TLS, PROTOCOL, CANCELLED, BODY_TOO_LARGE, NETWORK, ADDRESS_REJECTED }
    static final class TransportFailure extends IOException {
        final FailureReason reason;
        final boolean headersReceived;
        final boolean requestSent;
        TransportFailure(FailureReason reason, boolean headersReceived, boolean requestSent) {
            super(reason.name());
            this.reason = reason;
            this.headersReceived = headersReceived;
            this.requestSent = requestSent;
        }
        /** Only failures proven to happen before the request went on the wire may use the proxy once. */
        boolean fallbackAllowed() {
            return !headersReceived && !requestSent && switch (reason) {
                case DNS, CONNECT, NO_ROUTE -> true;
                default -> false;
            };
        }
    }
    @FunctionalInterface
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    record Reply(int status, String mediaType, String retryAfter, byte[] body) { }

    public ShowApiLookupResponse lookup(String isbn) {
        if (!IsbnUtils.isValid(isbn) || !isbn.equals(IsbnUtils.normalize(isbn))) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "ISBN 必须规范化且校验通过");
        }
        ShowApiSettings settings = config.snapshot();
        if (!settings.enabled() || settings.appKey() == null) {
            throw ApiException.of(ErrorCode.SHOWAPI_NOT_CONFIGURED);
        }
        if (closed.get()) { throw unavailable("CANCELLED", "METADATA", RouteKind.DIRECT, settings); }
        if (!permit.tryAcquire()) { throw rateLimited(1, "BUSY"); }
        long start = ticker.getAsLong();
        long deadline = start + 14 * SECOND;
        var query = new QueryContext(proxySettings.snapshot(), settings.revision());
        String outcome = "OK";
        try {
            long now = ticker.getAsLong();
            if (coolingDown && cooldownUntil - now > 0) { throw rateLimited(seconds(cooldownUntil - now), "COOLDOWN"); }
            coolingDown = false;
            if (lastMetadata != 0 && now - lastMetadata < SECOND) {
                throw rateLimited(seconds(SECOND - (now - lastMetadata)), "THROTTLED");
            }
            lastMetadata = now;
            URI metadataUri = URI.create("https://route.showapi.com/1626-1?appKey="
                    + URLEncoder.encode(settings.appKey(), StandardCharsets.UTF_8));
            byte[] form = ("isbn=" + URLEncoder.encode(isbn, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            Reply metadata = exchange(metadataUri, "POST", form, MAX_METADATA, deadline, query);
            if (metadata.status() == 429) { throw serverRateLimit(metadata.retryAfter()); }
            if (metadata.status() == 401 || metadata.status() == 403) {
                throw new ApiException(ErrorCode.SHOWAPI_AUTH_FAILED, ErrorCode.SHOWAPI_AUTH_FAILED.getDefaultMessage(),
                        Map.of("reason", "HTTP_AUTH", "stage", query.stage), List.of());
            }
            if (metadata.status() != 200) { throw query.failure("HTTP_ERROR"); }
            JsonNode root;
            try {
                root = mapper.readTree(metadata.body());
            } catch (JacksonException ex) {
                throw query.invalid("INVALID_JSON");
            }
            if (root == null || !root.isObject()) { throw query.invalid("INVALID_JSON"); }
            JsonNode resCode = root.get("showapi_res_code");
            if (resCode == null || !resCode.isIntegralNumber()) { throw query.invalid("ENVELOPE"); }
            if (resCode.asInt() != 0) { throw query.failure("UPSTREAM_ERROR"); }
            JsonNode body = root.get("showapi_res_body");
            if (body == null || !body.isObject()) { throw query.invalid("ENVELOPE"); }
            JsonNode retCode = body.get("ret_code");
            if (retCode == null || !retCode.isIntegralNumber()) { throw query.invalid("ENVELOPE"); }
            if (retCode.asInt() != 0) {
                // Business-level non-success: do not claim the book is definitely absent.
                return response(isbn, false, new Fields(null, null, null, null, null),
                        new Additional(null, null, null, null, null, null, null), null,
                        List.of(new Warning("LOOKUP_UNSUCCESSFUL", null, "查询未成功或未收录，可继续手工录入")), null);
            }
            JsonNode data = body.get("data");
            if (data == null || !data.isObject()) { throw query.invalid("DATA"); }
            JsonNode dataIsbn = data.get("isbn");
            if (dataIsbn == null || !dataIsbn.isTextual() || !IsbnUtils.isValid(dataIsbn.asText())
                    || !equivalent(IsbnUtils.normalize(dataIsbn.asText()), isbn)) {
                // Mismatched candidate: reject everything and never fetch the cover.
                throw query.invalid("ISBN_MISMATCH");
            }
            List<Warning> warnings = new ArrayList<>();
            String publicationDate = text(data.get("pubdate"), "publicationDate", 64, warnings);
            Fields fields = new Fields(text(data.get("title"), "title", 200, warnings),
                    text(data.get("author"), "author", 100, warnings),
                    text(data.get("publisher"), "publisher", 100, warnings),
                    text(data.get("gist"), "description", 2000, warnings),
                    year(publicationDate, warnings));
            Additional additional = new Additional(publicationDate,
                    text(data.get("edition"), "edition", 256, warnings),
                    text(data.get("page"), "pageCountText", 256, warnings),
                    text(data.get("format"), "format", 256, warnings),
                    text(data.get("paper"), "paper", 256, warnings),
                    text(data.get("binding"), "binding", 256, warnings),
                    text(data.get("price"), "priceText", 256, warnings));
            // "produce" has no documented meaning and is deliberately not mapped.
            URI coverUri = coverUri(data.get("img"), warnings);
            Cover cover = null;
            Integer retry = null;
            if (coverUri != null) {
                query.coverStage();
                try {
                    Reply image = exchange(coverUri, "GET", null, CoverImageValidator.MAX_BYTES, deadline, query);
                    if (image.status() == 429) {
                        retry = serverCooldown(image.retryAfter());
                        warn(warnings, "SHOWAPI_RATE_LIMITED", "cover", "封面请求限流，请稍后手动重试，书目信息仍可应用");
                    } else if (image.status() == 200) {
                        String type = image.mediaType() == null ? ""
                                : image.mediaType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                        var valid = validator.validate(type, image.body());
                        if (deadline - ticker.getAsLong() <= 0) { throw query.failure("TIMEOUT"); }
                        cover = new Cover(valid.mediaType(), Base64.getEncoder().encodeToString(valid.bytes()),
                                valid.bytes().length, valid.width(), valid.height());
                    } else {
                        // Includes 3xx: redirects are never followed for ShowAPI covers.
                        warn(warnings, "COVER_UNAVAILABLE", "cover", "封面获取失败，书目信息仍可应用");
                    }
                } catch (ApiException ex) {
                    if (ex.getCode() == ErrorCode.SHOWAPI_RATE_LIMITED) {
                        retry = (Integer) ex.getDetails().get("retryAfterSeconds");
                        warn(warnings, "SHOWAPI_RATE_LIMITED", "cover", "封面请求限流，请稍后手动重试，书目信息仍可应用");
                    } else {
                        warn(warnings, "COVER_UNAVAILABLE", "cover", "封面获取或校验失败，书目信息仍可应用");
                    }
                }
            }
            return response(isbn, true, fields, additional, cover, warnings, retry);
        } catch (ApiException ex) {
            outcome = ex.getCode().name();
            throw ex;
        } finally {
            long durationMs = (ticker.getAsLong() - start) / 1_000_000;
            // Enum-only diagnostics: never the AppKey, upstream URLs, raw errors or causes.
            log.info("showapi.lookup provider=SHOWAPI stage={} outcome={} route={} durationMs={} revision={}",
                    query.stage, outcome, query.lastRoute, durationMs, query.revision);
            transport.quiescence().whenComplete((ignored, error) -> permit.release());
        }
    }

    private static final class QueryContext {
        final ProxySnapshot settings;
        final long revision;
        String stage = "METADATA";
        RouteKind lastRoute = RouteKind.DIRECT;
        String directReason;
        QueryContext(ProxySnapshot settings, long revision) { this.settings = settings; this.revision = revision; }
        void coverStage() { stage = "COVER"; lastRoute = RouteKind.DIRECT; directReason = null; }
        ApiException failure(String reason) {
            return new ApiException(ErrorCode.SHOWAPI_UNAVAILABLE, ErrorCode.SHOWAPI_UNAVAILABLE.getDefaultMessage(),
                    details(reason), List.of());
        }
        ApiException invalid(String reason) {
            return new ApiException(ErrorCode.SHOWAPI_INVALID_RESPONSE, ErrorCode.SHOWAPI_INVALID_RESPONSE.getDefaultMessage(),
                    details(reason), List.of());
        }
        private Map<String, Object> details(String reason) {
            var details = new java.util.LinkedHashMap<String, Object>();
            details.put("reason", reason);
            details.put("stage", stage);
            details.put("lastRoute", lastRoute.name());
            details.put("proxyOutcome", !settings.enabled() ? "DISABLED"
                    : lastRoute == RouteKind.SOCKS5 ? "FAILED" : "NOT_ATTEMPTED");
            if (directReason != null) { details.put("directReason", directReason); }
            return details;
        }
    }

    private Reply exchange(URI uri, String method, byte[] form, int maxBytes, long deadline, QueryContext query) {
        try {
            return attempt(uri, method, form, Route.direct(), maxBytes, deadline, query);
        } catch (TransportFailure direct) {
            query.directReason = direct.reason.name();
            if (!query.settings.enabled() || !direct.fallbackAllowed() || deadline - ticker.getAsLong() < 1_000_000
                    || Thread.currentThread().isInterrupted() || closed.get()
                    || !transport.quiescence().toCompletableFuture().isDone()) {
                throw query.failure(Thread.currentThread().isInterrupted() || closed.get() ? "CANCELLED" : direct.reason.name());
            }
            try {
                return attempt(uri, method, form, Route.socks(query.settings), maxBytes, deadline, query);
            } catch (TransportFailure proxy) {
                throw query.failure(proxy.reason.name());
            }
        }
    }

    private Reply attempt(URI uri, String method, byte[] form, Route route, int maxBytes, long deadline, QueryContext query)
            throws TransportFailure {
        try {
            if (Thread.currentThread().isInterrupted() || closed.get()) { throw query.failure("CANCELLED"); }
            if (!transport.quiescence().toCompletableFuture().isDone()) { throw query.failure("TIMEOUT"); }
            long remaining = deadline - ticker.getAsLong();
            if (remaining < 1_000_000) { throw query.failure("TIMEOUT"); }
            if (coolingDown && cooldownUntil - ticker.getAsLong() > 0) {
                throw rateLimited(seconds(cooldownUntil - ticker.getAsLong()), "COOLDOWN");
            }
            query.lastRoute = route.kind();
            Reply reply = transport.exchange(uri, method, form, route, maxBytes,
                    Duration.ofNanos(Math.min(6 * SECOND, remaining)));
            if (deadline - ticker.getAsLong() <= 0) { throw query.failure("TIMEOUT"); }
            if (reply.body() != null && reply.body().length > maxBytes) { throw query.failure("BODY_TOO_LARGE"); }
            return reply;
        } catch (TransportFailure ex) {
            throw ex;
        } catch (ApiException ex) {
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

    private ApiException serverRateLimit(String raw) {
        return rateLimited(serverCooldown(raw), "UPSTREAM");
    }

    private int serverCooldown(String raw) {
        int retry = 60;
        if (raw != null && raw.trim().matches("[0-9]{1,3}")) {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed >= 1 && parsed <= 300) { retry = parsed; }
        }
        cooldownUntil = ticker.getAsLong() + retry * SECOND;
        coolingDown = true;
        return retry;
    }

    private static int seconds(long nanos) {
        return (int) Math.max(1, Math.min(300, (nanos + SECOND - 1) / SECOND));
    }

    private static ApiException rateLimited(int retry, String reason) {
        return new ApiException(ErrorCode.SHOWAPI_RATE_LIMITED, ErrorCode.SHOWAPI_RATE_LIMITED.getDefaultMessage(),
                Map.of("retryAfterSeconds", retry, "reason", reason), List.of());
    }

    private static ApiException unavailable(String reason, String stage, RouteKind route, ShowApiSettings settings) {
        return new ApiException(ErrorCode.SHOWAPI_UNAVAILABLE, ErrorCode.SHOWAPI_UNAVAILABLE.getDefaultMessage(),
                Map.of("reason", reason, "stage", stage, "lastRoute", route.name(), "revision", settings.revision()), List.of());
    }

    /** ISBN-10 <-> 978 ISBN-13 equivalence for candidate matching only; local storage rules are unchanged. */
    static boolean equivalent(String a, String b) {
        return a.equals(b) || to13(a).equals(b) || to13(b).equals(a);
    }

    private static String to13(String isbn) {
        if (isbn.length() == 13) { return isbn; }
        String base = "978" + isbn.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) { sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3); }
        return base + ((10 - sum % 10) % 10);
    }

    private static ShowApiLookupResponse response(String isbn, boolean found, Fields fields, Additional additional,
                                                  Cover cover, List<Warning> warnings, Integer retry) {
        return new ShowApiLookupResponse("SHOWAPI", isbn, found, SOURCE_URL, fields, additional,
                new Capabilities(false), cover, List.copyOf(warnings), retry);
    }

    private static void warn(List<Warning> warnings, String code, String field, String message) {
        warnings.add(new Warning(code, field, message));
    }

    private static String text(JsonNode value, String field, int max, List<Warning> warnings) {
        if (value == null || value.isNull()) { return null; }
        if (!value.isTextual()) {
            warn(warnings, "FIELD_INVALID", field, "上游字段类型不合法，保留人工录入");
            return null;
        }
        String result = value.asText().trim();
        if (result.length() > max) {
            warn(warnings, "FIELD_TOO_LONG", field, "上游字段过长，未截断或应用");
            return null;
        }
        return result.isEmpty() ? null : result;
    }

    private static Integer year(String publicationDate, List<Warning> warnings) {
        if (publicationDate == null) { return null; }
        var matcher = YEAR.matcher(publicationDate);
        if (!matcher.find()) {
            warn(warnings, "YEAR_AMBIGUOUS", "publishYear", "出版日期没有唯一四位年份");
            return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        if (matcher.find()) {
            warn(warnings, "YEAR_AMBIGUOUS", "publishYear", "出版日期含多个年份");
            return null;
        }
        return year;
    }

    /** Static-host JPEG/PNG paths: legacy imgs and observed numeric/hash directories; no URL credentials or encoding tricks. */
    static URI coverUri(JsonNode img, List<Warning> warnings) {
        if (img == null || img.isNull()) { return null; }
        if (img.isTextual()) {
            String raw = img.asText().trim();
            if (!raw.isEmpty() && raw.length() <= 256 && !raw.contains("%")) {
                try {
                    URI uri = URI.create(raw);
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                    boolean https = scheme.equals("https") && (uri.getPort() == -1 || uri.getPort() == 443);
                    boolean http = scheme.equals("http") && (uri.getPort() == -1 || uri.getPort() == 80);
                    if ((https || http) && "static1.showapi.com".equals(uri.getHost())
                            && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                            && uri.getRawPath() != null && COVER_PATH.matcher(uri.getRawPath()).matches()) {
                        return uri;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Never forward arbitrary or malformed upstream URLs.
                }
            }
        }
        warn(warnings, "COVER_REFERENCE_REJECTED", "cover", "上游封面引用不在许可范围，未发起请求");
        return null;
    }

    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) { transport.close(); }
    }
}