package com.example.library.auth;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.AuthTestHelper.AuthSession;
import com.example.library.support.IntegrationTestBase;
import com.example.library.support.TestDataFactory.ReaderFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口集成测试：AC-AUTH-01/02/06/07/08（后端部分）。
 * JSESSIONID 的 HttpOnly/SameSite 属性在真实 Tomcat 上验证（SessionCookieTest）。
 */
class AuthControllerTest extends IntegrationTestBase {

    private static final String ADMIN_PASSWORD = "Admin@12345";
    private static final String READER_PASSWORD = "Reader@123";

    @Test
    @DisplayName("AC-AUTH-01：管理员登录 200 UserInfo（role=ADMIN），随后 /me 同一用户")
    void adminLoginThenMe() throws Exception {
        var admin = testDataFactory.admin("auth-admin", ADMIN_PASSWORD);
        String token = authTestHelper.freshCsrfToken();
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth-admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId()))
                .andExpect(jsonPath("$.username").value("auth-admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.displayName").value("auth-admin"))
                .andExpect(jsonPath("$.readerId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cardNo").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId()))
                .andExpect(jsonPath("$.username").value("auth-admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("AC-AUTH-01：读者登录 200 UserInfo（role=READER，带 readerId/cardNo），随后 /me 同一用户")
    void readerLoginThenMe() throws Exception {
        ReaderFixture fixture = testDataFactory.reader("auth-reader", READER_PASSWORD, "认证读者", ReaderStatus.ACTIVE);
        AuthSession session = authTestHelper.loginAs("auth-reader", READER_PASSWORD);
        mockMvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.user().getId()))
                .andExpect(jsonPath("$.role").value("READER"))
                .andExpect(jsonPath("$.displayName").value("认证读者"))
                .andExpect(jsonPath("$.readerId").value(fixture.reader().getId()))
                .andExpect(jsonPath("$.cardNo").value(fixture.reader().getCardNo()));
        assertThat(fixture.reader().getCardNo()).matches("R\\d{6}");
    }

    @Test
    @DisplayName("AC-AUTH-01：用户名忽略大小写登录；登录成功后 Session ID 更换（防会话固定）")
    void loginIgnoresUsernameCaseAndRotatesSessionId() throws Exception {
        testDataFactory.admin("CaseAdmin", ADMIN_PASSWORD);
        MockHttpSession preExisting = new MockHttpSession();
        String oldId = preExisting.getId();
        String token = authTestHelper.freshCsrfToken();
        mockMvc.perform(post("/api/auth/login")
                        .session(preExisting)
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"caseadmin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("CaseAdmin"));
        assertThat(preExisting.getId()).isNotEqualTo(oldId);
    }

    @Test
    @DisplayName("AC-AUTH-02：用户名不存在与口令错误都返回 401 AUTH_INVALID_CREDENTIALS，message 相同")
    void invalidCredentialsUniformMessage() throws Exception {
        testDataFactory.admin("auth-admin", ADMIN_PASSWORD);
        String token = authTestHelper.freshCsrfToken();
        MvcResult unknownUser = mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no-such-user\",\"password\":\"Whatever123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn();
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth-admin\",\"password\":\"WrongPass123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn();
        String m1 = com.jayway.jsonpath.JsonPath.read(unknownUser.getResponse().getContentAsString(), "$.message");
        String m2 = com.jayway.jsonpath.JsonPath.read(wrongPassword.getResponse().getContentAsString(), "$.message");
        assertThat(m1).isEqualTo(m2);
    }

    @Test
    @DisplayName("AC-AUTH-02：停用账号用正确口令登录返回 401 AUTH_ACCOUNT_DISABLED，不建立会话")
    void disabledAccountCannotLogin() throws Exception {
        testDataFactory.reader("disabled-reader", READER_PASSWORD, "停用读者", ReaderStatus.DISABLED);
        String token = authTestHelper.freshCsrfToken();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disabled-reader\",\"password\":\"" + READER_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("AC-AUTH-02：登录请求体校验失败返回 400 VALIDATION_FAILED 且带 fieldErrors")
    void loginValidationErrors() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'username')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
    }

    @Test
    @DisplayName("AC-AUTH-06：已登录非 GET 请求缺失/错误 X-XSRF-TOKEN → 403 CSRF_INVALID 且会话未失效；正确 Token 成功")
    void csrfProtectionOnAuthenticatedRequests() throws Exception {
        testDataFactory.admin("csrf-admin", ADMIN_PASSWORD);
        AuthSession session = authTestHelper.loginAs("csrf-admin", ADMIN_PASSWORD);

        // 缺头（带 Cookie 也不行）
        mockMvc.perform(post("/api/auth/logout").session(session.session()).cookie(session.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        // 错误头
        mockMvc.perform(post("/api/auth/logout").session(session.session()).cookie(session.cookies())
                        .header("X-XSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        // 数据/会话未变化
        mockMvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk());
        // 正确 Token 成功
        mockMvc.perform(post("/api/auth/logout").session(session.session()).cookie(session.cookies())
                        .header("X-XSRF-TOKEN", session.csrfToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("AC-AUTH-06：登录请求同样受 CSRF 保护（匿名缺 Token → 403 CSRF_INVALID）")
    void loginRequiresCsrf() throws Exception {
        testDataFactory.admin("csrf-admin", ADMIN_PASSWORD);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"csrf-admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    @DisplayName("AC-AUTH-07：logout 204，之后同一 Cookie/会话调用 /me 返回 401")
    void logoutInvalidatesSession() throws Exception {
        testDataFactory.admin("logout-admin", ADMIN_PASSWORD);
        AuthSession session = authTestHelper.loginAs("logout-admin", ADMIN_PASSWORD);
        mockMvc.perform(post("/api/auth/logout").session(session.session()).cookie(session.cookies())
                        .header("X-XSRF-TOKEN", session.csrfToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("AC-AUTH-08：口令以 {bcrypt}$2 存储；login/me 响应不含 password/passwordHash；登录失败日志不含口令")
    void passwordStorageAndNoLeak() throws Exception {
        testDataFactory.admin("hash-admin", ADMIN_PASSWORD);
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE username = ?", String.class, "hash-admin");
        assertThat(hash).startsWith("{bcrypt}$2");

        Logger logger = (Logger) LoggerFactory.getLogger(AuthController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String token = authTestHelper.freshCsrfToken();
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"hash-admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String loginBody = login.getResponse().getContentAsString();
            assertThat(loginBody).doesNotContain("password").doesNotContain("passwordHash").doesNotContain(hash);
            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
            String meBody = mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(meBody).doesNotContain("password").doesNotContain("passwordHash").doesNotContain(hash);

            // 登录失败：日志不得出现口令
            mockMvc.perform(post("/api/auth/login")
                            .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"hash-admin\",\"password\":\"SecretWrong999\"}"))
                    .andExpect(status().isUnauthorized());
            List<ILoggingEvent> events = appender.list;
            assertThat(events).isNotEmpty();
            assertThat(events).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain("SecretWrong999").doesNotContain(ADMIN_PASSWORD));
        } finally {
            logger.detachAppender(appender);
        }
    }
}