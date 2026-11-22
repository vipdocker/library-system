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

    @Test
    void acOl02LookupIsPreviewOnlyWithFixedFieldsAndZeroDatabaseAccess() throws Exception {
        testDataFactory.book("080442957X", "已有人工书名", "人工作者", null);
        var time = new java.util.concurrent.atomic.AtomicLong(1);
        var deterministic = new OpenLibraryClient((uri, max, timeout) -> uri.getHost().equals("openlibrary.org")
                ? new OpenLibraryClient.Reply(200, "application/json", null,
                    "{\"ISBN:080442957X\":{\"title\":\"候选书名\",\"notes\":\"非简介\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : new OpenLibraryClient.Reply(404, "image/jpeg", null, new byte[0]),
                time::get, nanos -> time.addAndGet(nanos), new CoverImageValidator());
        org.mockito.Mockito.when(client.lookup("080442957X")).thenAnswer(invocation -> deterministic.lookup(invocation.getArgument(0)));
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = stats.isStatisticsEnabled(); stats.setStatisticsEnabled(true); stats.clear();
        org.mockito.Mockito.clearInvocations(covers);
        try {
            asAdmin(get("/api/catalog/open-library").param("isbn", "0-8044-2957-x"))
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
        org.mockito.Mockito.when(client.lookup("080442957X")).thenThrow(new com.example.library.common.error.ApiException(
                com.example.library.common.error.ErrorCode.OPEN_LIBRARY_UNAVAILABLE, "暂不可用", java.util.Map.of("reason", "TIMEOUT"), java.util.List.of()));
        asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OPEN_LIBRARY_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.reason").value("TIMEOUT")).andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        org.mockito.Mockito.doThrow(new com.example.library.common.error.ApiException(
                com.example.library.common.error.ErrorCode.OPEN_LIBRARY_RATE_LIMITED, "稍后重试", java.util.Map.of("retryAfterSeconds", 60), java.util.List.of()))
                .when(client).lookup("080442957X");
        asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty()).andExpect(jsonPath("$.details.retryAfterSeconds").value(60));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
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
    void acOl05LookupDoesNotAcceptArbitraryUrlOrIdentity() throws Exception {
        for (String name : new String[]{"url", "bookId", "readerId"}) {
            asAdmin(get("/api/catalog/open-library").param("isbn", "080442957X").param(name, "1"))
                    .andExpect(status().isBadRequest());
        }
    }
}
