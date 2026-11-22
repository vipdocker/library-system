package com.example.library.common.proxy;

import com.example.library.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProxySettingsApiTest extends IntegrationTestBase {
    private static final String PATH = "/api/admin/settings/proxy";

    private org.springframework.test.web.servlet.request.RequestPostProcessor validCsrf() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    @Test
    void acProxy01DefaultAndCommittedReplacementAreNoStoreAndAudited() throws Exception {
        mockMvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.host").isEmpty())
                .andExpect(jsonPath("$.port").isEmpty()).andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.updatedAt").isEmpty()).andExpect(jsonPath("$.updatedBy").doesNotExist());
        mockMvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(validCsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"host\":\"  BÜCHER.example  \",\"port\":9999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.host").value("xn--bcher-kva.example"))
                .andExpect(jsonPath("$.port").value(9999)).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.updatedAt").isString());
        assertThat(jdbcTemplate.queryForObject("SELECT updated_by FROM system_proxy_settings", String.class)).isEqualTo("admin");
        mockMvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(validCsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.host").isEmpty()).andExpect(jsonPath("$.port").isEmpty())
                .andExpect(jsonPath("$.revision").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"enabled\":null}", "{\"enabled\":\"true\"}", "{\"enabled\":1}",
            "{\"enabled\":true}", "{\"enabled\":false,\"host\":\"localhost\"}",
            "{\"enabled\":true,\"host\":7,\"port\":9999}", "{\"enabled\":true,\"host\":\"localhost\",\"port\":\"9999\"}",
            "{\"enabled\":true,\"host\":\"localhost\",\"port\":1.5}", "{\"enabled\":false,\"extra\":1}",
            "{\"enabled\":true,\"host\":\"localhost\",\"port\":0}", "{\"enabled\":true,\"host\":\"localhost\",\"port\":65536}",
            "{\"enabled\":true,\"host\":\"http://localhost\",\"port\":9999}", "[]", "null", ""})
    void acProxy01StrictFieldsRejectCoercionAndKeepDatabase(String body) throws Exception {
        mockMvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(validCsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM system_proxy_settings", Long.class)).isZero();
    }

    @Test
    void acProxy02DatabaseFailureReturnsSanitizedErrorAndKeepsSnapshot() throws Exception {
        for (String failure : new String[]{"SQLITE_BUSY", "fixture internal failure"}) {
            jdbcTemplate.execute("CREATE TRIGGER proxy_failure BEFORE UPDATE ON system_proxy_settings BEGIN SELECT RAISE(ABORT,'" + failure + "'); END");
            try {
                mockMvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(validCsrf())
                                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"host\":\"localhost\",\"port\":9999}"))
                        .andExpect(status().is(failure.equals("SQLITE_BUSY") ? 503 : 500))
                        .andExpect(jsonPath("$.code").value(failure.equals("SQLITE_BUSY") ? "DB_BUSY" : "INTERNAL_ERROR"))
                        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("localhost"))));
                mockMvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
                        .andExpect(jsonPath("$.revision").value(0)).andExpect(jsonPath("$.enabled").value(false));
                assertThat(jdbcTemplate.queryForObject("SELECT revision FROM system_proxy_settings", Long.class)).isZero();
            } finally { jdbcTemplate.execute("DROP TRIGGER proxy_failure"); }
        }
    }

    @Test
    void acProxy01AnonymousReaderAndMissingCsrfCannotMutate() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PATH).with(validCsrf()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        mockMvc.perform(put(PATH).with(user("reader").roles("READER")).with(validCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(PATH).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM system_proxy_settings", Long.class)).isZero();
    }
}
