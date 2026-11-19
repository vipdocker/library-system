package com.example.library.auth;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.AuthTestHelper.AuthSession;
import com.example.library.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修改本人口令集成测试（AC-AUTH-10，D4① 纳入；design-v0.1 第 7/8.4 节，T14）。
 * 覆盖：成功（204 + 会话保留 + 旧口令失效/新口令可登录 + BCrypt）、旧口令错误（400 AUTH_PASSWORD_MISMATCH，数据不变）、
 * 新口令不合规（400 VALIDATION_FAILED）、缺 CSRF（403）、未登录（401）、ADMIN/READER 两种角色、日志不泄露口令。
 */
class ChangePasswordTest extends IntegrationTestBase {

    private static final String ADMIN_OLD = "Admin@12345";
    private static final String READER_OLD = "Reader@123";
    private static final String NEW_PASSWORD = "NewPass@2026";

    private ResultActions putPassword(AuthSession session, String oldPassword, String newPassword) throws Exception {
        return mockMvc.perform(put("/api/auth/password")
                .session(session.session())
                .cookie(session.cookies())
                .header("X-XSRF-TOKEN", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(oldPassword, newPassword)));
    }

    /** 走真实登录接口，返回 HTTP 状态码（不预设成功，供新旧口令登录断言）。 */
    private int loginStatus(String username, String password) throws Exception {
        String token = authTestHelper.freshCsrfToken();
        return mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn().getResponse().getStatus();
    }

    private String storedHash(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE username = ?", String.class, username);
    }

    @Test
    @DisplayName("AC-AUTH-10：管理员修改成功 204；当前会话 /me 仍 200；旧口令登录 401、新口令登录 200；BCrypt 存储")
    void adminChangePasswordSuccess() throws Exception {
        var admin = testDataFactory.admin("pw-admin", ADMIN_OLD);
        String hashBefore = storedHash("pw-admin");
        AuthSession session = authTestHelper.loginAs("pw-admin", ADMIN_OLD);

        String body = putPassword(session, ADMIN_OLD, NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEmpty();

        // 当前会话保留
        mockMvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId()))
                .andExpect(jsonPath("$.username").value("pw-admin"));

        // 之后只能用新口令登录
        assertThat(loginStatus("pw-admin", ADMIN_OLD)).isEqualTo(401);
        assertThat(loginStatus("pw-admin", NEW_PASSWORD)).isEqualTo(200);

        // BCrypt 存储且哈希已更新（AC-AUTH-08 口径）
        String hashAfter = storedHash("pw-admin");
        assertThat(hashAfter).startsWith("{bcrypt}$2").isNotEqualTo(hashBefore);
    }

    @Test
    @DisplayName("AC-AUTH-10：读者修改成功 204；当前会话 /me 与 /api/readers/me 仍 200；旧口令登录 401、新口令登录 200")
    void readerChangePasswordSuccess() throws Exception {
        var fixture = testDataFactory.reader("pw-reader", READER_OLD, "口令读者", ReaderStatus.ACTIVE);
        AuthSession session = authTestHelper.loginAs("pw-reader", READER_OLD);

        putPassword(session, READER_OLD, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.user().getId()))
                .andExpect(jsonPath("$.role").value("READER"));
        mockMvc.perform(get("/api/readers/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.reader().getId()));

        assertThat(loginStatus("pw-reader", READER_OLD)).isEqualTo(401);
        assertThat(loginStatus("pw-reader", NEW_PASSWORD)).isEqualTo(200);
        assertThat(storedHash("pw-reader")).startsWith("{bcrypt}$2");
    }

    @Test
    @DisplayName("AC-AUTH-10：旧口令错误 400 AUTH_PASSWORD_MISMATCH，数据不变（旧口令仍可登录），日志不含口令")
    void wrongOldPasswordRejectedWithoutDataChange() throws Exception {
        testDataFactory.admin("pw-mismatch", ADMIN_OLD);
        String hashBefore = storedHash("pw-mismatch");
        AuthSession session = authTestHelper.loginAs("pw-mismatch", ADMIN_OLD);

        Logger logger = (Logger) LoggerFactory.getLogger(ChangePasswordService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String body = putPassword(session, "WrongOld999", NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("WrongOld999").doesNotContain(NEW_PASSWORD)
                    .doesNotContain(hashBefore);

            // 数据不变：哈希未动，旧口令仍可登录，新口令不可登录
            assertThat(storedHash("pw-mismatch")).isEqualTo(hashBefore);
            assertThat(loginStatus("pw-mismatch", ADMIN_OLD)).isEqualTo(200);
            assertThat(loginStatus("pw-mismatch", NEW_PASSWORD)).isEqualTo(401);

            List<ILoggingEvent> events = appender.list;
            assertThat(events).isNotEmpty();
            assertThat(events).allSatisfy(event ->
                    assertThat(event.getFormattedMessage())
                            .doesNotContain("WrongOld999").doesNotContain(NEW_PASSWORD)
                            .doesNotContain(ADMIN_OLD).doesNotContain(hashBefore));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", "12345678", "Ab1"})
    @DisplayName("AC-AUTH-10：新口令不满足策略（缺数字/缺字母/过短）400 VALIDATION_FAILED，数据不变")
    void newPasswordPolicyViolation(String invalidPassword) throws Exception {
        testDataFactory.admin("pw-policy", ADMIN_OLD);
        String hashBefore = storedHash("pw-policy");
        AuthSession session = authTestHelper.loginAs("pw-policy", ADMIN_OLD);

        putPassword(session, ADMIN_OLD, invalidPassword)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'newPassword')]").exists());

        assertThat(storedHash("pw-policy")).isEqualTo(hashBefore);
        assertThat(loginStatus("pw-policy", ADMIN_OLD)).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-AUTH-10：新口令超过 64 位 400 VALIDATION_FAILED，数据不变")
    void newPasswordTooLong() throws Exception {
        testDataFactory.admin("pw-long", ADMIN_OLD);
        String hashBefore = storedHash("pw-long");
        AuthSession session = authTestHelper.loginAs("pw-long", ADMIN_OLD);
        String tooLong = "A1" + "x".repeat(63); // 65 位

        putPassword(session, ADMIN_OLD, tooLong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'newPassword')]").exists());

        assertThat(storedHash("pw-long")).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("AC-AUTH-10：旧口令为空 400 VALIDATION_FAILED（fieldErrors 含 oldPassword），数据不变")
    void blankOldPasswordRejected() throws Exception {
        testDataFactory.admin("pw-blank", ADMIN_OLD);
        String hashBefore = storedHash("pw-blank");
        AuthSession session = authTestHelper.loginAs("pw-blank", ADMIN_OLD);

        putPassword(session, "", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'oldPassword')]").exists());

        assertThat(storedHash("pw-blank")).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("AC-AUTH-10：已登录但缺失/错误 X-XSRF-TOKEN 403 CSRF_INVALID，数据不变（AC-AUTH-06 口径）")
    void missingCsrfTokenRejected() throws Exception {
        testDataFactory.admin("pw-csrf", ADMIN_OLD);
        String hashBefore = storedHash("pw-csrf");
        AuthSession session = authTestHelper.loginAs("pw-csrf", ADMIN_OLD);
        String content = "{\"oldPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(ADMIN_OLD, NEW_PASSWORD);

        // 缺头（带 Cookie 也不行）
        mockMvc.perform(put("/api/auth/password")
                        .session(session.session()).cookie(session.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        // 错误头
        mockMvc.perform(put("/api/auth/password")
                        .session(session.session()).cookie(session.cookies())
                        .header("X-XSRF-TOKEN", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        assertThat(storedHash("pw-csrf")).isEqualTo(hashBefore);
        assertThat(loginStatus("pw-csrf", ADMIN_OLD)).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-AUTH-10：未登录（带有效 CSRF 但无会话）401 AUTH_REQUIRED")
    void unauthenticatedRejected() throws Exception {
        testDataFactory.admin("pw-anon", ADMIN_OLD);
        String hashBefore = storedHash("pw-anon");
        String token = authTestHelper.freshCsrfToken();

        mockMvc.perform(put("/api/auth/password")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(ADMIN_OLD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertThat(storedHash("pw-anon")).isEqualTo(hashBefore);
    }
}