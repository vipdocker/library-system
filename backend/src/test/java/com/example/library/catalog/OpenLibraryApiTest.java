package com.example.library.catalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OpenLibraryApiTest extends CatalogTestBase {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private OpenLibraryClient client;
    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private BookCoverRepository covers;

    @org.junit.jupiter.api.BeforeEach
    void quiescentClient() {
        org.mockito.Mockito.when(client.quiescence()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    @Test
    void cacheFlightStaysBusyUntilTransportQuiescenceEvenAfterLookupReturns() throws Exception {
        var cleanup = new java.util.concurrent.CompletableFuture<Void>();
        var returned = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(client.quiescence()).thenReturn(cleanup);
        org.mockito.Mockito.when(client.lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    returned.countDown();
                    return new com.example.library.catalog.dto.OpenLibraryLookupResponse("080442957X", false, "https://openlibrary.org/isbn/080442957X",
                            new com.example.library.catalog.dto.OpenLibraryLookupResponse.Fields(null, null, null, null, null), null, java.util.List.of(), null);
                });
        var first = asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X")).andExpect(request().asyncStarted()).andReturn();
        assertThat(returned.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        asAdmin(get("/api/catalog/open-library").param("isbn", "9780306406157"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.details.reason").value("BUSY"));
        cleanup.complete(null);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(first)).andExpect(status().isOk());
    }

    @Test
    void acOl02LookupIsPreviewOnlyWithFixedFieldsAndZeroDatabaseAccess() throws Exception {
        testDataFactory.book("080442957X", "已有人工书名", "人工作者", null);
        var time = new java.util.concurrent.atomic.AtomicLong(1);
        var deterministic = new OpenLibraryClient((uri, max, timeout) -> uri.getHost().equals("openlibrary.org")
                ? new OpenLibraryClient.Reply(200, "application/json", null, null,
                    "{\"key\":\"/books/OL1M\",\"isbn_10\":[\"080442957X\"],\"title\":\"候选书名\",\"notes\":\"非简介\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : new OpenLibraryClient.Reply(404, "image/jpeg", null, null, new byte[0]),
                time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
        stubLookup(deterministic);
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = stats.isStatisticsEnabled(); stats.setStatisticsEnabled(true); stats.clear();
        org.mockito.Mockito.clearInvocations(covers);
        try {
            lookupAsAdmin("0-8044-2957-x")
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.isbn").value("080442957X"))
                    .andExpect(jsonPath("$.found").value(true))
                    .andExpect(jsonPath("$.sourceUrl").value("https://openlibrary.org/isbn/080442957X"))
                    .andExpect(jsonPath("$.fields.title").value("候选书名"))
                    .andExpect(jsonPath("$.fields.author").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.fields.publisher").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.fields.description").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.fields.publishYear").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.cover").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.warnings").isArray())
                    .andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.nullValue()));
            assertThat(stats.getPrepareStatementCount()).isZero();
            org.mockito.Mockito.verifyNoInteractions(covers);
        } finally { stats.setStatisticsEnabled(previous); }
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM book", String.class)).isEqualTo("已有人工书名");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_copy", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
    }

    @Test
    void acOl04LookupErrorsKeepUnifiedResponseAndRateHeader() throws Exception {
        org.mockito.Mockito.when(client.lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any())).thenThrow(new com.example.library.common.error.ApiException(
                com.example.library.common.error.ErrorCode.OPEN_LIBRARY_UNAVAILABLE, "暂不可用", java.util.Map.of("reason", "TIMEOUT"), java.util.List.of()));
        lookupAsAdmin("080442957X")
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OPEN_LIBRARY_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.reason").value("TIMEOUT")).andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        org.mockito.Mockito.doThrow(new com.example.library.common.error.ApiException(
                com.example.library.common.error.ErrorCode.OPEN_LIBRARY_RATE_LIMITED, "稍后重试", java.util.Map.of("retryAfterSeconds", 60), java.util.List.of()))
                .when(client).lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any());
        lookupAsAdmin("080442957X")
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty()).andExpect(jsonPath("$.details.retryAfterSeconds").value(60));
        org.mockito.Mockito.doThrow(new com.example.library.common.error.ApiException(
                com.example.library.common.error.ErrorCode.OPEN_LIBRARY_RATE_LIMITED, "稍后重试", java.util.Map.of("longWait", true, "reason", "COOLDOWN"), java.util.List.of()))
                .when(client).lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any());
        lookupAsAdmin("080442957X")
                .andExpect(status().isTooManyRequests()).andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.details.longWait").value(true)).andExpect(jsonPath("$.details.retryAfterSeconds").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
    }

    // ---- OL-OPT-CACHE-01: async single flight with observable unsubscribe ----
    @Test
    void cacheConcurrentSameIsbnRequestsMergeIntoOneUpstreamLookup() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var answer = new com.example.library.catalog.dto.OpenLibraryLookupResponse("080442957X", false, "https://openlibrary.org/isbn/080442957X",
                new com.example.library.catalog.dto.OpenLibraryLookupResponse.Fields(null, null, null, null, null), null, java.util.List.of(), null);
        org.mockito.Mockito.when(client.lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> { calls.incrementAndGet(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return answer; });
        var first = asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X")).andExpect(request().asyncStarted()).andReturn();
        var second = asAdmin(get("/api/catalog/open-library").param("isbn", "0-8044-2957-X")).andExpect(request().asyncStarted()).andReturn();
        asAdmin(get("/api/catalog/open-library").param("isbn", "9780306406157"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.details.reason").value("BUSY"));
        release.countDown();
        for (var started : java.util.List.of(first, second)) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.isbn").value("080442957X"));
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cacheAsyncDisconnectUnsubscribesAndLastSubscriberInterruptsTheWorker() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1); var interrupted = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(client.lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            started.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ex) { interrupted.countDown(); }
            throw new com.example.library.common.error.ApiException(com.example.library.common.error.ErrorCode.OPEN_LIBRARY_UNAVAILABLE,
                    "cancelled", java.util.Map.of("reason", "CANCELLED"), java.util.List.of());
        });
        var result = asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X")).andExpect(request().asyncStarted()).andReturn();
        assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var context = (org.springframework.mock.web.MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onError(new jakarta.servlet.AsyncEvent(context, new java.io.IOException("client aborted")));
        }
        assertThat(interrupted.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void acOl05LookupRequiresAdminAndStrictIsbn() throws Exception {
        anon(get("/api/catalog/open-library").param("isbn", "080442957X"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        asReader(get("/api/catalog/open-library").param("isbn", "080442957X"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (String isbn : new String[]{"123", "9787115428029", "https://openlibrary.org/isbn/080442957X"}) {
            asAdmin(get("/api/catalog/open-library").param("isbn", isbn))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("isbn"));
        }
    }

    @Test
    void diagnosticStatusIsExplicitNullWhenNoHeadersArrived() throws Exception {
        var upstream = new OpenLibraryClient((uri, max, timeout) -> { throw new java.io.IOException("transmission"); },
                System::nanoTime, nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos), new CoverImageValidator());
        stubLookup(upstream);
        lookupAsAdmin("080442957X").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.details").value(org.hamcrest.Matchers.hasKey("upstreamStatus")))
                .andExpect(jsonPath("$.details.upstreamStatus").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void diagUpstream401NeverBecomesLocal401AndKeepsAdminSession() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(1);
        var upstream = new OpenLibraryClient((uri, max, timeout) -> new OpenLibraryClient.Reply(401, "application/json", null, null, new byte[0]),
                time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
        stubLookup(upstream);
        lookupAsAdmin("080442957X")
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OPEN_LIBRARY_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.upstreamStatus").value(401)).andExpect(jsonPath("$.details.stage").value("METADATA"))
                .andExpect(jsonPath("$.details.provider").value("OPEN_LIBRARY")).andExpect(header().string("Cache-Control", "no-store"));
        asAdmin(get("/api/auth/me")).andExpect(status().isOk());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void diagReactorNettyConnectLoggerIsOffAndRealProxyFailureLeaksNoAddress(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("reactor.netty.http.client.HttpClientConnect");
        assertThat(logger.getEffectiveLevel()).isEqualTo(ch.qos.logback.classic.Level.OFF);
        assertThat(((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).getLevel())
                .isNotEqualTo(ch.qos.logback.classic.Level.OFF);
        try (var fixture = new SocksTlsFixture(); var transport = new ReactorNettyTransport(fixture.clientTls(), fixture.directAddresses())) {
            fixture.rejectAuth = true;
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> transport.get(ReactorNettyTransportTest.META,
                    new OpenLibraryClient.Route(OpenLibraryClient.RouteKind.SOCKS5, "localhost", fixture.proxyPort()), 256 * 1024,
                    java.time.Duration.ofSeconds(3))).isInstanceOf(OpenLibraryClient.TransportFailure.class);
            assertThat(output.getAll()).doesNotContain("HttpClientConnect", ":" + fixture.proxyPort(), "ProxyConnectException");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private OpenLibraryProperties openLibraryProperties;

    @Test
    void policyPropertiesBindFilteredProjectVersionAndSafeDefaults() {
        assertThat(openLibraryProperties.getAppVersion()).matches("[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?");
        assertThat(openLibraryProperties.getContactEmail()).isEmpty();
        assertThat(openLibraryProperties.getDirectFailureTtl()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(openLibraryProperties.metadataUserAgent()).isEqualTo("LibraryCatalog/" + openLibraryProperties.getAppVersion());
    }

    @Test
    void acOl05LookupDoesNotAcceptArbitraryUrlOrIdentity() throws Exception {
        for (String name : new String[]{"url", "bookId", "readerId"}) {
            asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X").param(name, "1"))
                    .andExpect(status().isBadRequest());
        }
    }

    private void stubLookup(OpenLibraryClient real) {
        org.mockito.Mockito.when(client.lookup(org.mockito.ArgumentMatchers.eq("080442957X"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> real.lookup(invocation.getArgument(0)));
    }

    /** OL-OPT-CACHE: successful lookups are asynchronous (DeferredResult); synchronous rejections are returned directly. */
    private org.springframework.test.web.servlet.ResultActions lookupAsAdmin(String isbn) throws Exception {
        var actions = asAdmin(get("/api/catalog/open-library").param("isbn", isbn));
        var result = actions.andReturn();
        return result.getRequest().isAsyncStarted()
                ? mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result)) : actions;
    }
}
