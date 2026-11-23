package com.example.library.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AC-S4: admin-only configuration, never echoes the key, optimistic revision, strict body, 4KiB stream cap. */
class ShowApiSettingsApiTest extends CatalogTestBase {
    private static final String FAKE_KEY = "FAKE-TEST-KEY-000000";
    private static final String PATH = "/api/admin/settings/showapi";

    private MockHttpServletRequestBuilder putJson(String json) {
        return put(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void acS4DefaultViewIsDisabledWithoutKeyAndRequiresAdmin() throws Exception {
        anon(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        asReader(get(PATH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        var result = asAdmin(get(PATH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.appKeyConfigured").value(false))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.updatedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.appKey").doesNotExist())
                .andReturn();
        assertThat(body(result)).doesNotContain("appKey\"");
    }

    @Test
    void acS4ReplaceKeepClearLifecycleNeverEchoesKey() throws Exception {
        var replaced = asAdmin(putJson("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.appKeyConfigured").value(true))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();
        assertThat(body(replaced)).doesNotContain(FAKE_KEY).doesNotContain("FAKE-TEST");
        assertThat(jdbcTemplate.queryForObject("SELECT app_key FROM showapi_settings WHERE id=1", String.class)).isEqualTo(FAKE_KEY);
        assertThat(jdbcTemplate.queryForObject("SELECT updated_by FROM showapi_settings WHERE id=1", String.class)).isEqualTo("catalog-admin");

        var kept = asAdmin(putJson("{\"enabled\":true,\"appKeyAction\":\"KEEP\",\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.appKeyConfigured").value(true)).andReturn();
        assertThat(body(kept)).doesNotContain(FAKE_KEY);
        assertThat(jdbcTemplate.queryForObject("SELECT app_key FROM showapi_settings WHERE id=1", String.class)).isEqualTo(FAKE_KEY);

        // CLEAR is only allowed together with disabled.
        asAdmin(putJson("{\"enabled\":true,\"appKeyAction\":\"CLEAR\",\"expectedRevision\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(putJson("{\"enabled\":false,\"appKeyAction\":\"CLEAR\",\"expectedRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.appKeyConfigured").value(false)).andExpect(jsonPath("$.revision").value(3));
        assertThat(jdbcTemplate.queryForObject("SELECT app_key FROM showapi_settings WHERE id=1", String.class)).isNull();
        // Enabling again without a key is rejected and changes nothing.
        asAdmin(putJson("{\"enabled\":true,\"appKeyAction\":\"KEEP\",\"expectedRevision\":3}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM showapi_settings WHERE id=1", Long.class)).isEqualTo(3);
    }

    @Test
    void acS4StrictBodyValidationRejectsBadShapesWithoutEchoingValues() throws Exception {
        String longKey = "FAKE-LONG-" + "x".repeat(512);
        String[] invalid = {
                "[]",
                "{\"enabled\":\"yes\",\"appKeyAction\":\"KEEP\",\"expectedRevision\":0}",
                "{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\"}",
                "{\"enabled\":false,\"appKeyAction\":\"KEEP\",\"expectedRevision\":-1}",
                "{\"enabled\":false,\"appKeyAction\":\"KEEP\",\"expectedRevision\":\"0\"}",
                "{\"enabled\":false,\"appKeyAction\":\"OTHER\",\"expectedRevision\":0}",
                "{\"enabled\":false,\"appKeyAction\":\"KEEP\",\"expectedRevision\":0,\"extra\":1}",
                "{\"enabled\":false,\"appKeyAction\":\"KEEP\",\"appKey\":null,\"expectedRevision\":0}",
                "{\"enabled\":false,\"appKeyAction\":\"CLEAR\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}",
                "{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"   \",\"expectedRevision\":0}",
                "{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":123,\"expectedRevision\":0}",
                "{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"expectedRevision\":0}",
        };
        for (String json : invalid) {
            var result = asAdmin(putJson(json)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn();
            assertThat(body(result)).doesNotContain(FAKE_KEY);
        }
        var tooLong = asAdmin(putJson("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + longKey + "\",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(body(tooLong)).doesNotContain("FAKE-LONG").doesNotContain("xxxx");
        // Duplicate JSON keys are rejected, not silently merged.
        asAdmin(putJson("{\"enabled\":false,\"enabled\":true,\"appKeyAction\":\"KEEP\",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM showapi_settings WHERE id=1", Long.class)).isZero();
    }

    @Test
    void acS4RevisionConflictReturns409AndKeepsCommittedState() throws Exception {
        asAdmin(putJson("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}"))
                .andExpect(status().isOk());
        asAdmin(putJson("{\"enabled\":false,\"appKeyAction\":\"CLEAR\",\"expectedRevision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_CONFLICT"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM showapi_settings WHERE id=1", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT app_key FROM showapi_settings WHERE id=1", String.class)).isEqualTo(FAKE_KEY);
        asAdmin(get(PATH)).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.appKeyConfigured").value(true));
    }

    @Test
    void acS4CsrfAndAnonymousWritesAreRejectedBeforeAnyChange() throws Exception {
        mockMvc.perform(put(PATH).session(admin.session()).cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        // Existing chain semantics: a write without any CSRF token is rejected by the CSRF filter
        // before authentication (403 CSRF_INVALID); with a valid anonymous token it is 401.
        anon(putJson("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        String anonToken = authTestHelper.freshCsrfToken();
        mockMvc.perform(put(PATH).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", anonToken))
                        .header("X-XSRF-TOKEN", anonToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        asReader(putJson("{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"" + FAKE_KEY + "\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM showapi_settings WHERE id=1", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT app_key FROM showapi_settings WHERE id=1", String.class)).isNull();
    }

    @Test
    void acS4BodyOver4KiBIs413EvenWithoutContentLength() throws Exception {
        String filler = "a".repeat(4200);
        String json = "{\"enabled\":false,\"appKeyAction\":\"KEEP\",\"expectedRevision\":0,\"filler\":\"" + filler + "\"}";
        asAdmin(putJson(json)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        // Same body with the Content-Length header suppressed: the real stream must still be counted.
        MockHttpServletRequestBuilder noLength = putJson(json).with(request -> {
            request.setContent(request.getContentAsByteArray());
            request.removeHeader("Content-Length");
            return request;
        });
        asAdmin(noLength).andExpect(status().isPayloadTooLarge());
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM showapi_settings WHERE id=1", Long.class)).isZero();
    }
}