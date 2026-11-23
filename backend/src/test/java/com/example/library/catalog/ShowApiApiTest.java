package com.example.library.catalog;

import java.util.List;

import com.example.library.catalog.dto.ShowApiLookupResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AC-S1/S2/T1: explicit admin-only query, preview only, strict body, unified errors, no-store. */
class ShowApiApiTest extends CatalogTestBase {
    @MockitoBean
    private ShowApiClient client;

    private static final String PATH = "/api/catalog/showapi";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query(String json) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static ShowApiLookupResponse preview(String isbn) {
        return new ShowApiLookupResponse("SHOWAPI", isbn, true, "https://www.showapi.com/apis/isbn-book-1626",
                new ShowApiLookupResponse.Fields("候选书名", "作者", null, null, 2020),
                new ShowApiLookupResponse.Additional("2020-05", null, null, null, null, null, null),
                new ShowApiLookupResponse.Capabilities(false), null, List.of(), null);
    }

    @Test
    void acS2QueryIsPreviewOnlyWithNoDatabaseAccess() throws Exception {
        testDataFactory.book("080442957X", "已有人工书名", "人工作者", null);
        Mockito.when(client.lookup("080442957X")).thenReturn(preview("080442957X"));
        var factory = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = factory.isStatisticsEnabled();
        factory.setStatisticsEnabled(true);
        factory.clear();
        try {
            asAdmin(query("{\"isbn\":\"0-8044-2957-x\"}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.provider").value("SHOWAPI"))
                    .andExpect(jsonPath("$.isbn").value("080442957X"))
                    .andExpect(jsonPath("$.found").value(true))
                    .andExpect(jsonPath("$.sourceUrl").value("https://www.showapi.com/apis/isbn-book-1626"))
                    .andExpect(jsonPath("$.fields.title").value("候选书名"))
                    .andExpect(jsonPath("$.fields.publishYear").value(2020))
                    .andExpect(jsonPath("$.additional.publicationDate").value("2020-05"))
                    .andExpect(jsonPath("$.additional.priceText").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.capabilities.tableOfContents").value(false))
                    .andExpect(jsonPath("$.cover").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.warnings").isArray())
                    .andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.nullValue()));
            assertThat(factory.getPrepareStatementCount()).isZero();
        } finally {
            factory.setStatisticsEnabled(previous);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM book", String.class)).isEqualTo("已有人工书名");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
        Mockito.verify(client).lookup("080442957X");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Test
    void t1AuthAndCsrfFailBeforeAnyOutbound() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        mockMvc.perform(post(PATH).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        asReader(query("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post(PATH).session(admin.session()).cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void acS2StrictBodyAndIsbnValidation() throws Exception {
        String[] invalid = {
                "{}", "[]", "\"080442957X\"",
                "{\"isbn\":42}", "{\"isbn\":null}",
                "{\"isbn\":\"080442957X\",\"extra\":1}",
                "{\"isbn\":\"080442957X\",\"isbn\":\"9780804429573\"}",
                "",
        };
        for (String json : invalid) {
            asAdmin(query(json)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        asAdmin(post(PATH).param("isbn", "080442957X").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"080442957X\"}")).andExpect(status().isBadRequest());
        for (String isbn : new String[]{"123", "9787115428029", "https://evil"}) {
            asAdmin(query("{\"isbn\":\"" + isbn + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("isbn"));
        }
        String oversized = "{\"isbn\":\"080442957X\",\"pad\":\"" + "a".repeat(4200) + "\"}";
        asAdmin(query(oversized)).andExpect(status().isPayloadTooLarge());
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void t1UnifiedErrorsCarryRetryAfterAndNoStore() throws Exception {
        Mockito.when(client.lookup("080442957X")).thenThrow(ApiException.withDetails(
                ErrorCode.SHOWAPI_RATE_LIMITED, java.util.Map.of("retryAfterSeconds", 60, "reason", "COOLDOWN")));
        asAdmin(query("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("SHOWAPI_RATE_LIMITED"));
        Mockito.doThrow(ApiException.of(ErrorCode.SHOWAPI_NOT_CONFIGURED)).when(client).lookup("080442957X");
        asAdmin(query("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOWAPI_NOT_CONFIGURED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        Mockito.doThrow(ApiException.of(ErrorCode.SHOWAPI_AUTH_FAILED)).when(client).lookup("080442957X");
        asAdmin(query("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SHOWAPI_AUTH_FAILED"));
        Mockito.doThrow(ApiException.of(ErrorCode.SHOWAPI_INVALID_RESPONSE)).when(client).lookup("080442957X");
        asAdmin(query("{\"isbn\":\"080442957X\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SHOWAPI_INVALID_RESPONSE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
    }
}