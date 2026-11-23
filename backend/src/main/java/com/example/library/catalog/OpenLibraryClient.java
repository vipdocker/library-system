package com.example.library.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenLibraryClient.class);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long WINDOW = TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_METADATA = 256 * 1024;
    private static final Pattern COVER_PATH = Pattern.compile("/b/id/([1-9][0-9]{0,18})-[SML]\\.(?:jpg|jpeg|png)");
    private static final Pattern COVER_RESOURCE_PATH = Pattern.compile("/b/(?:isbn/[0-9X]{10,13}|id/[1-9][0-9]{0,18})-M\\.jpg");
    private static final Pattern ARCHIVE_PATH = Pattern.compile("/download/m_covers_([0-9]{4})/m_covers_\\1_([0-9]{2})\\.zip/([0-9]{10})-M\\.jpg");
    private final Transport transport;
    private final com.example.library.common.proxy.ProxySnapshotProvider proxySettings;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final LongSupplier ticker;
    private final Sleeper sleeper;
    private final CoverImageValidator validator;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Semaphore permit = new Semaphore(1);
    private final OpenLibraryRequestPolicy policy;
    /** Bump whenever field mapping changes so stale mapped entries are never served. */
    static final String CACHE_VERSION = OpenLibraryMetadataMapper.VERSION;
    /** Real outbound attempts per query, counting DIRECT/SOCKS fallbacks, the edition redirect and cover hops. */
    static final int MAX_ATTEMPTS = 8;
    private static final Pattern EDITION_PATH = Pattern.compile("/books/(OL[1-9][0-9]{0,9}M)\\.json");
    private static final Pattern ISBN_PATH = Pattern.compile("/isbn/(?:[0-9]{9}[0-9X]|[0-9]{13})\\.json");
    private static final Pattern WORK_PATH = Pattern.compile("/works/OL[1-9][0-9]{0,9}W\\.json");
    private static final Pattern SEARCH_QUERY = Pattern.compile("q=isbn:(?:[0-9]{9}[0-9X]|[0-9]{13})&fields=key,author_key,author_name&limit=1");
    private static final long DAY = TimeUnit.HOURS.toNanos(24);
    private final OpenLibraryLookupState.TtlCache<String, MetadataEntry> metadata;
    private final OpenLibraryLookupState.TtlCache<String, CoverBytes> covers;
    private final OpenLibraryLookupState.TtlCache<String, Boolean> negatives;

    @Autowired
    public OpenLibraryClient(CoverImageValidator validator, com.example.library.common.proxy.ProxySnapshotProvider proxySettings,
                             OpenLibraryProperties properties) {
        this(new ReactorNettyTransport(properties.metadataUserAgent(), properties.assetUserAgent()), System::nanoTime,
                nanos -> TimeUnit.NANOSECONDS.sleep(nanos), validator, proxySettings, properties.getDirectFailureTtl());
    }

    OpenLibraryClient(Transport transport, LongSupplier ticker, Sleeper sleeper, CoverImageValidator validator) {
        this(transport, ticker, sleeper, validator, () -> new com.example.library.common.proxy.ProxySnapshot(false, null, null, 0, null));
    }

    OpenLibraryClient(Transport transport, LongSupplier ticker, Sleeper sleeper, CoverImageValidator validator,
                      com.example.library.common.proxy.ProxySnapshotProvider proxySettings) {
        this(transport, ticker, sleeper, validator, proxySettings, Duration.ofSeconds(30));
    }

    OpenLibraryClient(Transport transport, LongSupplier ticker, Sleeper sleeper, CoverImageValidator validator,
                      com.example.library.common.proxy.ProxySnapshotProvider proxySettings, Duration directFailureTtl) {
        this.transport = transport;
        this.proxySettings = proxySettings;
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.validator = validator;
        this.policy = new OpenLibraryRequestPolicy(ticker, java.time.Clock.systemUTC(), directFailureTtl);
        this.metadata = new OpenLibraryLookupState.TtlCache<>(ticker, DAY, 100, 8L * 1024 * 1024, 8L * 1024 * 1024, MetadataEntry::weight);
        this.covers = new OpenLibraryLookupState.TtlCache<>(ticker, DAY, 64, 16L * 1024 * 1024, CoverImageValidator.MAX_BYTES,
                cover -> cover.bytes().length);
        this.negatives = new OpenLibraryLookupState.TtlCache<>(ticker, 60 * SECOND, 256, 256L * 128, 128, key -> 128);
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
    /** TIMEOUT = TCP connect timeout; HANDSHAKE_TIMEOUT = TCP up but SOCKS/TLS handshake stalled; RESPONSE_TIMEOUT = request sent, no headers. */
    enum FailureReason { DNS, CONNECT, NO_ROUTE, RESET, TIMEOUT, HANDSHAKE_TIMEOUT, RESPONSE_TIMEOUT, TLS, PROTOCOL, CANCELLED, BODY_TOO_LARGE, NETWORK }
    static final class TransportFailure extends IOException {
        final FailureReason reason;
        final boolean headersReceived;
        /** Real upstream status/Retry-After, only when response headers arrived before the failure; never guessed. */
        final Integer status;
        final String retryAfter;
        TransportFailure(FailureReason reason, boolean headersReceived) { this(reason, headersReceived, null, null); }
        TransportFailure(FailureReason reason, boolean headersReceived, Integer status, String retryAfter) {
            super(reason.name()); this.reason = reason; this.headersReceived = headersReceived;
            this.status = headersReceived ? status : null; this.retryAfter = headersReceived ? retryAfter : null;
        }
        boolean fallbackAllowed() {
            return !headersReceived && switch (reason) {
                case DNS, CONNECT, NO_ROUTE, RESET, TIMEOUT, HANDSHAKE_TIMEOUT, RESPONSE_TIMEOUT -> true;
                default -> false;
            };
        }
        /** Only header-less DNS/refused/no-route/reset/TCP-connect-timeout may be remembered; TLS/handshake/HTTP never. */
        boolean learnable() {
            return !headersReceived && switch (reason) {
                case DNS, CONNECT, NO_ROUTE, RESET, TIMEOUT -> true;
                default -> false;
            };
        }
    }
    @FunctionalInterface
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    record Reply(int status, String mediaType, String retryAfter, String location, byte[] body) { }

    /** Immutable cached metadata (found editions only); trusted not-found results live in the short negative cache. */
    record MetadataEntry(Fields fields, String coverUri, List<Warning> warnings) {
        MetadataEntry { warnings = List.copyOf(warnings); }
        long weight() {
            long chars = coverUri == null ? 0 : coverUri.length();
            for (String value : new String[]{fields.title(), fields.author(), fields.publisher(), fields.description()}) {
                chars += value == null ? 0 : value.length();
            }
            for (Warning warning : warnings) { chars += warning.message().length() + warning.code().length() + 64; }
            return 256 + 2 * chars;
        }
    }
    /** Validated raw image bytes only: no decoded bitmap and no Base64 copy is retained. */
    record CoverBytes(String mediaType, byte[] bytes, int width, int height) {
        Cover toResponse() { return new Cover(mediaType, Base64.getEncoder().encodeToString(bytes), bytes.length, width, height); }
    }

    private static String metadataKey(String isbn) { return "edition|" + CACHE_VERSION + "|" + isbn; }
    private static String coverKey(String uri) { return "cover|" + uri; }

    /** Full cache hit (metadata plus settled cover state, or a trusted not-found): no permit, no policy, no outbound. */
    public java.util.Optional<OpenLibraryLookupResponse> cached(String isbn) {
        if (closed.get() || isbn == null) { return java.util.Optional.empty(); }
        if (negatives.get(metadataKey(isbn)) != null) { return java.util.Optional.of(notFound(isbn)); }
        MetadataEntry meta = metadata.get(metadataKey(isbn));
        if (meta == null) { return java.util.Optional.empty(); }
        if (meta.coverUri() == null) { return java.util.Optional.of(response(isbn, true, meta.fields(), null, meta.warnings(), null)); }
        CoverBytes cover = covers.get(meta.coverUri());
        if (cover != null) { return java.util.Optional.of(response(isbn, true, meta.fields(), cover.toResponse(), meta.warnings(), null)); }
        return negatives.get(coverKey(meta.coverUri())) != null
                ? java.util.Optional.of(response(isbn, true, meta.fields(), null, meta.warnings(), null)) : java.util.Optional.empty();
    }

    public OpenLibraryLookupResponse lookup(String isbn) {
        return lookup(isbn, null);
    }

    /** One query with an immutable proxy snapshot (read once when not supplied). */
    public OpenLibraryLookupResponse lookup(String isbn, com.example.library.common.proxy.ProxySnapshot snapshot) {
        if (!IsbnUtils.isValid(isbn) || !isbn.equals(IsbnUtils.normalize(isbn))) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "ISBN 必须规范化且校验通过");
        }
        if (closed.get()) { throw unavailable("CANCELLED"); }
        var hit = cached(isbn);
        if (hit.isPresent()) { return hit.get(); }
        if (!permit.tryAcquire()) {
            throw OpenLibraryRequestPolicy.rateLimited(SECOND, "BUSY");
        }
        long deadline = ticker.getAsLong() + 14 * SECOND;
        try {
            var query = new QueryContext(snapshot != null ? snapshot : proxySettings.snapshot());
            MetadataEntry meta = metadata.get(metadataKey(isbn));
            boolean cacheable = false;
            String pendingWork = null;
            if (meta == null) {
                if (negatives.get(metadataKey(isbn)) != null) { return notFound(isbn); }
                Draft draft;
                try {
                    draft = fetchMetadata(isbn, deadline, query);
                } catch (OpenLibraryMetadataMapper.EditionMismatch mismatch) {
                    // Never borrow another edition's fields or cover; not cached (not a trusted not-found).
                    return response(isbn, false, new Fields(null, null, null, null, null), null, List.of(new Warning("EDITION_MISMATCH", null,
                            "上游返回的版本与该 ISBN 不一致，未采用任何字段或封面", query.details("EDITION_MISMATCH"))), null);
                }
                if (draft == null) { store(negatives, metadataKey(isbn), Boolean.TRUE); return notFound(isbn); }
                meta = draft.entry(); pendingWork = draft.pendingWork(); cacheable = !draft.transientIssue();
            }
            OpenLibraryLookupResponse result = coverStage(isbn, meta, deadline, query);
            if (pendingWork != null) {
                List<Warning> coverWarnings = result.warnings().subList(meta.warnings().size(), result.warnings().size());
                Draft work = workStage(meta, pendingWork, deadline, query);
                meta = work.entry(); cacheable = cacheable && !work.transientIssue();
                var merged = new ArrayList<Warning>(meta.warnings().subList(0, meta.warnings().size()));
                merged.addAll(coverWarnings);
                result = response(isbn, true, meta.fields(), result.cover(), merged, result.retryAfterSeconds());
            }
            if (cacheable) { store(metadata, metadataKey(isbn), meta); }
            for (Warning warning : result.warnings()) {
                if (warning.details() != null) { report(false, warning.code(), warning.details()); }
            }
            return result;
        } catch (ApiException ex) {
            if (ex.getCode() != ErrorCode.VALIDATION_FAILED) { report(true, ex.getCode().name(), ex.getDetails()); }
            throw ex;
        } finally {
            transport.quiescence().whenComplete((ignored, error) -> permit.release());
        }
    }

    private static OpenLibraryLookupResponse notFound(String isbn) {
        return response(isbn, false, new Fields(null, null, null, null, null), null, List.of(), null);
    }

    /** Edition metadata plus what the lookup still has to do after the cover stage. */
    private record Draft(MetadataEntry entry, String pendingWork, boolean transientIssue) { }

    /**
     * Edition API: GET /isbn/{isbn}.json, at most one strict same-origin HTTPS redirect to /books/OL..M.json, then
     * (only when the Edition references authors) one verified Search request. Returns null for a trusted not-found;
     * throws for failures (never cached as not-found) and EditionMismatch when the Edition does not belong to the ISBN.
     */
    private Draft fetchMetadata(String isbn, long deadline, QueryContext query) {
        URI isbnUri = URI.create("https://openlibrary.org/isbn/" + isbn + ".json");
        query.target(isbnUri);
        Reply edition = exchange(isbnUri, MAX_METADATA, deadline, query);
        String expectedKey = null;
        if (edition.status() == 404) { query.confirm(policy); return null; }
        if (isRedirect(edition.status())) {
            URI target = editionRedirect(isbnUri, edition.location(), query);
            query.confirm(policy);
            var key = EDITION_PATH.matcher(target.getRawPath());
            expectedKey = key.matches() ? "/books/" + key.group(1) : null;
            if (query.lastRoute == RouteKind.SOCKS5) {
                // The SOCKS route that answered the /isbn step is pinned for its single redirect hop.
                try { edition = attempt(target, Route.socks(query.settings), MAX_METADATA, deadline, query); }
                catch (TransportFailure failure) { throw query.failure(failure.reason.name()); }
            } else {
                edition = exchange(target, MAX_METADATA, deadline, query);
            }
            if (edition.status() == 404) { query.confirm(policy); return null; }
            if (isRedirect(edition.status())) { throw query.failure("METADATA_REDIRECT_LIMIT"); }
        }
        if (edition.status() != 200) { throw query.failure("HTTP_ERROR"); }
        List<Warning> warnings = new ArrayList<>();
        OpenLibraryMetadataMapper.Edition parsed;
        try {
            parsed = OpenLibraryMetadataMapper.edition(parse(edition, query), isbn, expectedKey, warnings);
        } catch (OpenLibraryMetadataMapper.InvalidPayload invalid) {
            throw query.failure("INVALID_JSON");
        }
        query.confirm(policy);
        boolean transientIssue = false;
        String author = null;
        if (!parsed.authorKeys().isEmpty()) {
            if (parsed.workKey() == null) {
                author = OpenLibraryMetadataMapper.authors(null, parsed, warnings);
            } else if (!optionalAllowed(deadline, query)) {
                warnings.add(new Warning("AUTHOR_SKIPPED", "author", "查询时间或次数预算不足，未核对作者，书目其余信息仍可应用",
                        query.details("BUDGET")));
                transientIssue = true;
            } else {
                URI search = URI.create("https://openlibrary.org/search.json?q=isbn:" + isbn + "&fields=key,author_key,author_name&limit=1");
                try {
                    query.target(search);
                    Reply reply = exchange(search, MAX_METADATA, deadline, query);
                    if (reply.status() == 200) {
                        JsonNode searchResult = parse(reply, query);
                        JsonNode docs = searchResult.isObject() ? searchResult.get("docs") : null;
                        if (docs == null || !docs.isArray()) { throw query.failure("INVALID_JSON"); }
                        author = OpenLibraryMetadataMapper.authors(searchResult, parsed, warnings);
                        query.confirm(policy);
                    } else {
                        warnings.add(new Warning("AUTHOR_UNAVAILABLE", "author", "作者查询失败，未采用作者，书目其余信息仍可应用",
                                query.details("HTTP_ERROR")));
                        transientIssue = true;
                    }
                } catch (ApiException ex) {
                    warnings.add(new Warning("AUTHOR_UNAVAILABLE", "author", "作者查询失败，未采用作者，书目其余信息仍可应用",
                            optionalDetails(ex, query)));
                    transientIssue = true;
                }
            }
        }
        String coverUri = parsed.coverId() != null ? "https://covers.openlibrary.org/b/id/" + parsed.coverId() + "-M.jpg?default=false"
                : parsed.coversPresent() ? null : "https://covers.openlibrary.org/b/isbn/" + isbn + "-M.jpg?default=false";
        Fields fields = new Fields(parsed.title(), author, parsed.publisher(), parsed.description(), parsed.publishYear());
        // Work fallback is only for a missing Edition description, never for a rejected/overlong Edition field.
        boolean rejectedDescription = warnings.stream().anyMatch(warning -> "description".equals(warning.field())
                && ("FIELD_INVALID".equals(warning.code()) || "FIELD_TOO_LONG".equals(warning.code())));
        String pendingWork = parsed.description() == null && !rejectedDescription ? parsed.workKey() : null;
        return new Draft(new MetadataEntry(fields, coverUri, warnings), pendingWork, transientIssue);
    }

    /** Optional Search/Work steps only run with enough of the 14 s budget and of the 8-attempt cap left. */
    private boolean optionalAllowed(long deadline, QueryContext query) {
        return deadline - ticker.getAsLong() >= 3 * SECOND && query.attempts < MAX_ATTEMPTS;
    }

    private static java.util.LinkedHashMap<String, Object> optionalDetails(ApiException ex, QueryContext query) {
        Object reason = ex.getDetails().get("reason");
        var details = query.details(reason == null ? ex.getCode().name() : reason.toString());
        if (ex.getDetails().containsKey("retryAfterSeconds")) { details.put("retryAfterSeconds", ex.getDetails().get("retryAfterSeconds")); }
        if (ex.getDetails().containsKey("longWait")) { details.put("longWait", true); }
        return details;
    }

    private JsonNode parse(Reply reply, QueryContext query) {
        try {
            JsonNode root = mapper.readTree(reply.body());
            if (root == null) { throw query.failure("INVALID_JSON"); }
            return root;
        } catch (JacksonException ex) {
            throw query.failure("INVALID_JSON");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** The only metadata redirect accepted: same-origin HTTPS /books/OL..M.json, no query/fragment/userinfo/port/encoding/dots. */
    private static URI editionRedirect(URI from, String location, QueryContext query) {
        if (location == null || location.isBlank()) { throw query.failure("METADATA_REDIRECT_MISSING_LOCATION"); }
        try {
            if (location.length() > 256) { throw new IllegalArgumentException(); }
            URI reference = URI.create(location);
            String raw = reference.getRawPath();
            if (raw == null || raw.contains("%") || java.util.Arrays.asList(raw.split("/")).contains("..")
                    || java.util.Arrays.asList(raw.split("/")).contains(".")) {
                throw new IllegalArgumentException();
            }
            URI target = from.resolve(reference);
            if (!"https".equals(target.getScheme()) || !"openlibrary.org".equals(target.getHost()) || target.getPort() != -1
                    || target.getRawUserInfo() != null || target.getRawQuery() != null || target.getRawFragment() != null
                    || !EDITION_PATH.matcher(target.getRawPath()).matches()) {
                throw new IllegalArgumentException();
            }
            return target;
        } catch (IllegalArgumentException rejected) {
            throw query.failure("METADATA_REDIRECT_REJECTED");
        }
    }

    /** Single verified Work description, only after the cover stage and only within budget; never notes or HTML. */
    private Draft workStage(MetadataEntry meta, String workKey, long deadline, QueryContext query) {
        List<Warning> warnings = new ArrayList<>(meta.warnings());
        URI work = URI.create("https://openlibrary.org/works/" + workKey + ".json");
        query.metadataStage();
        query.target(work);
        if (!optionalAllowed(deadline, query)) {
            warnings.add(new Warning("DESCRIPTION_SKIPPED", "description", "查询时间或次数预算不足，未获取作品级简介，书目其余信息仍可应用",
                    query.details("BUDGET")));
            return new Draft(new MetadataEntry(meta.fields(), meta.coverUri(), warnings), null, true);
        }
        try {
            Reply reply = exchange(work, MAX_METADATA, deadline, query);
            if (reply.status() == 404) { query.confirm(policy); return new Draft(meta, null, false); }
            if (reply.status() != 200) {
                warnings.add(new Warning("DESCRIPTION_UNAVAILABLE", "description", "作品级简介获取失败，书目其余信息仍可应用", query.details("HTTP_ERROR")));
                return new Draft(new MetadataEntry(meta.fields(), meta.coverUri(), warnings), null, true);
            }
            JsonNode record = parse(reply, query);
            JsonNode key = record.isObject() ? record.get("key") : null;
            if (key == null || !key.isString() || !("/works/" + workKey).equals(key.asString())) {
                throw query.failure("WORK_UNVERIFIED");
            }
            String description = OpenLibraryMetadataMapper.workDescription(record, workKey, warnings);
            query.confirm(policy);
            if (description == null) { return new Draft(new MetadataEntry(meta.fields(), meta.coverUri(), warnings), null, false); }
            warnings.add(new Warning("DESCRIPTION_FROM_WORK", "description", "简介来自作品级记录，可能与本版本不完全一致，请核对后再应用"));
            Fields f = meta.fields();
            return new Draft(new MetadataEntry(new Fields(f.title(), f.author(), f.publisher(), description, f.publishYear()), meta.coverUri(), warnings), null, false);
        } catch (ApiException ex) {
            warnings.add(new Warning("DESCRIPTION_UNAVAILABLE", "description", "作品级简介获取失败，书目其余信息仍可应用", optionalDetails(ex, query)));
            return new Draft(new MetadataEntry(meta.fields(), meta.coverUri(), warnings), null, true);
        }
    }

    /** Cover from the raw-byte cache, the short negative cache, or the network; failures are never cached. */
    private OpenLibraryLookupResponse coverStage(String isbn, MetadataEntry meta, long deadline, QueryContext query) {
        {
            List<Warning> warnings = new ArrayList<>(meta.warnings());
            Fields fields = meta.fields();
            CoverBytes cached = meta.coverUri() == null ? null : covers.get(meta.coverUri());
            URI coverUri = meta.coverUri() == null || cached != null || negatives.get(coverKey(meta.coverUri())) != null
                    ? null : URI.create(meta.coverUri());
            Cover cover = cached == null ? null : cached.toResponse();
            Integer retry = null;
            if (coverUri != null) {
                try {
                    query.coverStage();
                    Reply image = exchangeCover(coverUri, deadline, query);
                    if (image.status() == 200) {
                        String type = image.mediaType() == null ? "" : image.mediaType().split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                        var valid = validator.validate(type, image.body());
                        if (deadline - ticker.getAsLong() <= 0) { throw query.failure("TIMEOUT"); }
                        query.confirm(policy);
                        store(covers, meta.coverUri(), new CoverBytes(valid.mediaType(), valid.bytes().clone(), valid.width(), valid.height()));
                        cover = new Cover(valid.mediaType(), Base64.getEncoder().encodeToString(valid.bytes()),
                                valid.bytes().length, valid.width(), valid.height());
                    } else if (image.status() == 404) {
                        query.confirm(policy);
                        store(negatives, coverKey(meta.coverUri()), Boolean.TRUE);
                    } else {
                        warnings.add(new Warning("COVER_UNAVAILABLE", "cover", "封面获取失败，书目信息仍可应用", query.details("HTTP_ERROR")));
                    }
                } catch (ApiException ex) {
                    if (ex.getCode() == ErrorCode.OPEN_LIBRARY_RATE_LIMITED) {
                        retry = (Integer) ex.getDetails().get("retryAfterSeconds");
                    }
                    String reason = (String) ex.getDetails().get("reason");
                    String code = reason != null && reason.startsWith("COVER_REDIRECT_") ? reason
                            : "COVER_REJECTED".equals(reason) || "COVER_COOLDOWN".equals(reason) ? "COVER_REJECTED" : ex.getCode().name();
                    String message = switch (code) {
                        case "COVER_REDIRECT_MISSING_LOCATION" -> "封面跳转缺少目标地址，未继续请求，书目信息仍可应用";
                        case "COVER_REDIRECT_REJECTED" -> "封面跳转目标不在许可范围，未继续请求，书目信息仍可应用";
                        case "COVER_REDIRECT_LOOP" -> "封面跳转出现循环，已停止请求，书目信息仍可应用";
                        case "COVER_REDIRECT_LIMIT" -> "封面跳转超过两次，已停止请求，书目信息仍可应用";
                        case "COVER_REJECTED" -> "封面服务拒绝了按 ISBN 的封面请求（可能已触发限流），请稍后手动重试，书目信息仍可应用";
                        case "OPEN_LIBRARY_RATE_LIMITED" -> query.archiveCover && "UPSTREAM".equals(reason)
                                ? "封面归档服务限流，请稍后手动重试，书目信息仍可应用"
                                : "封面请求限流，请稍后手动重试，书目信息仍可应用";
                        default -> "封面获取或校验失败，书目信息仍可应用";
                    };
                    var details = query.details(reason == null ? code : reason);
                    if (ex.getDetails().containsKey("retryAfterSeconds")) { details.put("retryAfterSeconds", ex.getDetails().get("retryAfterSeconds")); }
                    if (ex.getDetails().containsKey("longWait")) { details.put("longWait", true); }
                    warnings.add(new Warning(code, "cover", message, details));
                }
            }
            return response(isbn, true, fields, cover, warnings, retry);
        }
    }

    /** Sanitized application log: only enum/integer diagnostics we produce; never ISBN, URL, proxy address or raw exceptions. */
    private static void report(boolean failed, String code, Map<String, Object> details) {
        if (failed) {
            log.warn("Open Library lookup failed: code={} reason={} stage={} provider={} upstreamStatus={} route={} proxyOutcome={}",
                    code, details.get("reason"), details.get("stage"), details.get("provider"), details.get("upstreamStatus"),
                    details.get("lastRoute"), details.get("proxyOutcome"));
        } else {
            log.info("Open Library lookup partial: warning={} reason={} stage={} provider={} upstreamStatus={} route={} proxyOutcome={}",
                    code, details.get("reason"), details.get("stage"), details.get("provider"), details.get("upstreamStatus"),
                    details.get("lastRoute"), details.get("proxyOutcome"));
        }
    }

    private static final class QueryContext {
        final com.example.library.common.proxy.ProxySnapshot settings;
        String stage = "METADATA";
        String provider = "OPEN_LIBRARY";
        RouteKind lastRoute = RouteKind.DIRECT;
        Integer upstreamStatus;
        String directReason;
        String proxyReason;
        boolean socksAttempted;
        boolean socksResponded;
        boolean archiveCover;
        OpenLibraryRequestPolicy.DirectState directState = OpenLibraryRequestPolicy.DirectState.COLD;
        /** Host whose DIRECT network failure is learned only once the SOCKS response is validated by the caller. */
        String pendingLearn;
        int attempts;
        QueryContext(com.example.library.common.proxy.ProxySnapshot settings) { this.settings = settings; }
        /** Work description happens after the cover stage: diagnostics return to the metadata provider. */
        void metadataStage() {
            stage = "METADATA"; provider = "OPEN_LIBRARY"; lastRoute = RouteKind.DIRECT; directReason = null; proxyReason = null;
            upstreamStatus = null; socksAttempted = false; socksResponded = false; pendingLearn = null;
            directState = OpenLibraryRequestPolicy.DirectState.COLD; archiveCover = false;
        }
        void confirm(OpenLibraryRequestPolicy policy) {
            if (pendingLearn != null) { policy.learnDirectFailure(pendingLearn, settings); pendingLearn = null; }
        }
        void coverStage() {
            stage = "COVER"; provider = "COVERS"; lastRoute = RouteKind.DIRECT; directReason = null; proxyReason = null;
            upstreamStatus = null; socksAttempted = false; socksResponded = false; pendingLearn = null;
            directState = OpenLibraryRequestPolicy.DirectState.COLD;
        }
        void target(URI uri) {
            provider = "archive.org".equals(uri.getHost()) ? "ARCHIVE" : "covers.openlibrary.org".equals(uri.getHost()) ? "COVERS" : "OPEN_LIBRARY";
        }
        void starting(Route route) {
            lastRoute = route.kind(); upstreamStatus = null; pendingLearn = null;
            if (route.kind() == RouteKind.SOCKS5) { socksAttempted = true; socksResponded = false; proxyReason = null; }
        }
        void responded(int status) {
            upstreamStatus = status;
            if (lastRoute == RouteKind.SOCKS5) { socksResponded = true; }
        }
        void failed(TransportFailure failure) {
            upstreamStatus = failure.status;
            if (lastRoute == RouteKind.SOCKS5) { socksResponded = failure.headersReceived; proxyReason = failure.reason.name(); }
            else { directReason = failure.reason.name(); }
        }
        String proxyOutcome() {
            return !settings.enabled() ? "DISABLED" : !socksAttempted ? "NOT_ATTEMPTED" : socksResponded ? "RESPONDED" : "FAILED";
        }
        java.util.LinkedHashMap<String, Object> details(String reason) {
            var details = new java.util.LinkedHashMap<String, Object>();
            details.put("reason", reason); details.put("stage", stage); details.put("provider", provider);
            details.put("lastRoute", lastRoute.name()); details.put("proxyOutcome", proxyOutcome());
            // Common ApiException/ErrorResponse defensively use Map.copyOf (no Java null values).
            // Jackson's null node preserves that invariant while rendering the explicitly nullable status as JSON null.
            details.put("upstreamStatus", upstreamStatus == null ? tools.jackson.databind.node.NullNode.getInstance() : upstreamStatus);
            if (directReason != null) { details.put("directReason", directReason); }
            if (proxyReason != null) { details.put("proxyReason", proxyReason); }
            if (directState != OpenLibraryRequestPolicy.DirectState.COLD) { details.put("directState", directState.name()); }
            return details;
        }
        ApiException failure(String reason) {
            return new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(), details(reason), List.of());
        }
    }

    private Reply exchangeCover(URI original, long deadline, QueryContext query) {
        URI uri = original;
        var visited = new LinkedHashSet<URI>();
        visited.add(uri);
        for (int hops = 0; ; hops++) {
            query.archiveCover = "archive.org".equals(uri.getHost());
            query.target(uri);
            Reply reply;
            // A successful SOCKS cover response pins the redirect chain to that same snapshot route.
            if (query.lastRoute == RouteKind.SOCKS5) {
                try { reply = attempt(uri, Route.socks(query.settings), CoverImageValidator.MAX_BYTES, deadline, query); }
                catch (TransportFailure failure) { throw query.failure(failure.reason.name()); }
            } else {
                reply = exchange(uri, CoverImageValidator.MAX_BYTES, deadline, query);
            }
            if (reply.status() != 301 && reply.status() != 302 && reply.status() != 303 && reply.status() != 307 && reply.status() != 308) {
                return reply;
            }
            if (hops >= 2) { throw query.failure("COVER_REDIRECT_LIMIT"); }
            String location = reply.location();
            if (location == null || location.isBlank()) { throw query.failure("COVER_REDIRECT_MISSING_LOCATION"); }
            URI target;
            try {
                if (location.length() > 256) { throw new IllegalArgumentException(); }
                URI reference = URI.create(location);
                // Reject dot/encoded path ambiguity before resolve() can normalize it away.
                if (reference.getRawPath() != null && (reference.getRawPath().contains("%")
                        || java.util.Arrays.asList(reference.getRawPath().split("/")).contains("..")
                        || java.util.Arrays.asList(reference.getRawPath().split("/")).contains("."))) {
                    throw new IllegalArgumentException();
                }
                target = uri.resolve(reference);
                var id = COVER_PATH.matcher(original.getRawPath());
                if (!permittedCoverResource(target) || !("covers.openlibrary.org".equals(target.getHost())
                        && original.getRawPath().equals(target.getRawPath())
                        || id.matches() && id.group(1).equals(archiveCoverId(target)))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException failure) { throw query.failure("COVER_REDIRECT_REJECTED"); }
            if (!visited.add(target)) { throw query.failure("COVER_REDIRECT_LOOP"); }
            query.confirm(policy);
            uri = target;
        }
    }

    /** Shared transport guard for openlibrary.org: exact Edition-API endpoints only, never Legacy /api/books or arbitrary URLs. */
    static boolean permittedMetadataResource(URI uri) {
        if (uri == null || uri.toASCIIString().length() > 256 || !"https".equals(uri.getScheme()) || !"openlibrary.org".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getRawPath() == null || uri.getRawPath().contains("%")) { return false; }
        String path = uri.getRawPath();
        if (ISBN_PATH.matcher(path).matches() || EDITION_PATH.matcher(path).matches() || WORK_PATH.matcher(path).matches()) {
            return uri.getRawQuery() == null;
        }
        return "/search.json".equals(path) && uri.getRawQuery() != null && SEARCH_QUERY.matcher(uri.getRawQuery()).matches();
    }

    /** Shared transport guard; only the observed archive layout is allowed, not arbitrary archive items/storage hosts. */
    static boolean permittedCoverResource(URI uri) {
        if (uri == null || uri.toASCIIString().length() > 256 || !"https".equals(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getRawPath() == null) { return false; }
        return "covers.openlibrary.org".equals(uri.getHost()) && COVER_RESOURCE_PATH.matcher(uri.getRawPath()).matches()
                && (uri.getRawQuery() == null || "default=false".equals(uri.getRawQuery()))
                || archiveCoverId(uri) != null;
    }

    private static String archiveCoverId(URI uri) {
        if (!"archive.org".equals(uri.getHost()) || uri.getRawQuery() != null || uri.getRawPath() == null) { return null; }
        var path = ARCHIVE_PATH.matcher(uri.getRawPath());
        if (!path.matches() || !path.group(3).startsWith(path.group(1) + path.group(2))) { return null; }
        long id = Long.parseLong(path.group(3));
        return id > 0 ? Long.toString(id) : null;
    }

    private Reply exchange(URI uri, int maxBytes, long deadline, QueryContext query) {
        String host = uri.getHost();
        var state = policy.directState(host, query.settings);
        query.directState = state;
        if (state == OpenLibraryRequestPolicy.DirectState.SKIP) {
            // Known DIRECT network failure for this host+proxy revision within the short TTL: one SOCKS attempt only.
            try { return attempt(uri, Route.socks(query.settings), maxBytes, deadline, query); }
            catch (TransportFailure proxy) { throw query.failure(proxy.reason.name()); }
        }
        try {
            return attempt(uri, Route.direct(), maxBytes, deadline, query);
        } catch (TransportFailure direct) {
            if (!query.settings.enabled() || !direct.fallbackAllowed() || deadline - ticker.getAsLong() < 1_000_000
                    || Thread.currentThread().isInterrupted() || closed.get() || !transport.quiescence().toCompletableFuture().isDone()) {
                throw query.failure(Thread.currentThread().isInterrupted() || closed.get() ? "CANCELLED" : direct.reason.name());
            }
            Reply proxied;
            try { proxied = attempt(uri, Route.socks(query.settings), maxBytes, deadline, query); }
            catch (TransportFailure proxy) { throw query.failure(proxy.reason.name()); }
            if (direct.learnable()) { query.pendingLearn = host; }
            return proxied;
        }
    }

    private Reply attempt(URI uri, Route route, int maxBytes, long deadline, QueryContext query) throws TransportFailure {
        try {
            if (Thread.currentThread().isInterrupted() || closed.get()) { throw query.failure("CANCELLED"); }
            if (!transport.quiescence().toCompletableFuture().isDone()) { throw query.failure("TIMEOUT"); }
            if (deadline - ticker.getAsLong() < 1_000_000) { throw query.failure("TIMEOUT"); }
            if (query.attempts >= MAX_ATTEMPTS) { throw query.failure("ATTEMPT_LIMIT"); }
            policy.reserve(uri, deadline, sleeper);
            long remaining = deadline - ticker.getAsLong();
            if (remaining < 1_000_000) { throw query.failure("TIMEOUT"); }
            query.starting(route);
            query.attempts++;
            Reply reply;
            try { reply = transport.get(uri, route, maxBytes, Duration.ofNanos(Math.min(6 * SECOND, remaining))); }
            catch (TransportFailure failure) {
                query.failed(failure);
                if (failure.status != null) {
                    ApiException throttled = policy.onResponse(uri, failure.status, failure.retryAfter);
                    if (throttled != null) { throw throttled; }
                }
                throw failure;
            }
            query.responded(reply.status());
            // DIRECT reached the upstream (any HTTP status): the host is not DIRECT-unreachable, so forget any memo.
            if (route.kind() == RouteKind.DIRECT) { policy.clearDirectFailure(uri.getHost()); }
            ApiException throttled = policy.onResponse(uri, reply.status(), reply.retryAfter());
            if (throttled != null) { throw throttled; }
            if (deadline - ticker.getAsLong() <= 0) { throw query.failure("TIMEOUT"); }
            if (reply.body().length > maxBytes) { throw query.failure("BODY_TOO_LARGE"); }
            return reply;
        } catch (TransportFailure ex) {
            throw ex;
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCode.OPEN_LIBRARY_UNAVAILABLE) { throw query.failure((String) ex.getDetails().get("reason")); }
            throw new ApiException(ex.getCode(), ex.getMessage(), optionalDetails(ex, query), ex.getFieldErrors());
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

    private static ApiException unavailable(String reason) {
        return new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(),
                Map.of("reason", reason), List.of());
    }

    private static OpenLibraryLookupResponse response(String isbn, boolean found, Fields fields, Cover cover,
                                                       List<Warning> warnings, Integer retry) {
        return new OpenLibraryLookupResponse(isbn, found, "https://openlibrary.org/isbn/" + isbn,
                fields, cover, List.copyOf(warnings), retry);
    }

    /** Owned transport cleanup; used by the single-flight worker so no new lookup starts before channels are reclaimed. */
    public CompletionStage<Void> quiescence() { return transport.quiescence(); }

    private synchronized <K, V> void store(OpenLibraryLookupState.TtlCache<K, V> cache, K key, V value) {
        if (!closed.get() && !Thread.currentThread().isInterrupted()) { cache.put(key, value); }
    }

    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (this) { metadata.clear(); covers.clear(); negatives.clear(); }
            transport.close();
        }
    }

}
