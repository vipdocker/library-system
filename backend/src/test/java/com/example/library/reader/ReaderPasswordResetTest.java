package com.example.library.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-RDR-05：管理员重置读者口令 → 204；之后只能用新口令登录（旧口令 401 AUTH_INVALID_CREDENTIALS）；
 * 新口令不合规 → 400 + fieldErrors[newPassword]，原口令不受影响；读者不存在 → 404。
 */
class ReaderPasswordResetTest extends ReaderTestBase {

    @Test
    @DisplayName("AC-RDR-05：重置口令 → 204，旧口令失效，新口令可登录，哈希以 {bcrypt}$2 存储")
    void resetPasswordSuccess() throws Exception {
        long id = readerA.reader().getId();
        String oldHash = jdbcTemplate.queryForObject("SELECT password_hash FROM app_user WHERE id = ?",
                String.class, readerA.user().getId());
        MvcResult result = asAdmin(put("/api/readers/" + id + "/password")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"NewPass123\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(body(result)).isEmpty();
        String newHash = jdbcTemplate.queryForObject("SELECT password_hash FROM app_user WHERE id = ?",
                String.class, readerA.user().getId());
        assertThat(newHash).startsWith("{bcrypt}$2").isNotEqualTo(oldHash);

        MvcResult oldLogin = attemptLogin("reader-a", READER_PASSWORD);
        assertThat(oldLogin.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body(oldLogin), "$.code"))
                .isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(attemptLogin("reader-a", "NewPass123").getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "AC-RDR-05 新口令不合规 [{0}] → 400")
    @ValueSource(strings = {"Ab1", "abcdefgh", "12345678", "abcdefgh12345678abcdefgh12345678abcdefgh12345678abcdefgh12345678abc1"})
    @DisplayName("AC-RDR-05：新口令不合规 → 400 + fieldErrors[newPassword]，原口令仍可登录")
    void invalidNewPasswordRejected(String password) throws Exception {
        asAdmin(put("/api/readers/" + readerA.reader().getId() + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"%s\"}".formatted(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'newPassword')]").isNotEmpty());
        assertThat(attemptLogin("reader-a", READER_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-RDR-05：缺少 newPassword → 400；读者不存在 → 404 READER_NOT_FOUND")
    void missingPasswordOrReader() throws Exception {
        asAdmin(put("/api/readers/" + readerA.reader().getId() + "/password")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'newPassword')]").isNotEmpty());
        asAdmin(put("/api/readers/999999/password")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"NewPass123\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
    }
}