package com.example.library.security;

import com.example.library.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1/I-02（design-v0.1-R1 §8.2 METHOD_NOT_ALLOWED，approval-record §1.1）：
 * 请求方法不被端点支持 → 405 + METHOD_NOT_ALLOWED，统一 JSON 错误结构。
 * 同时验证安全链顺序：认证/授权在方法解析之前，405 不能绕过 401/403（AC-AUTH-03/04）。
 * 探针端点：PUT /api/auth/password（仅 PUT）、POST /api/auth/login（仅 POST）。
 */
class MethodNotAllowedTest extends IntegrationTestBase {

    @Test
    @DisplayName("R1/I-02 + AC-AUTH-03：未登录对 PUT-only 端点发 GET → 401 AUTH_REQUIRED（405 不绕过认证）")
    void anonymousWrongMethodStill401() throws Exception {
        mockMvc.perform(get("/api/auth/password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("R1/I-02 + AC-AUTH-03：未登录对 POST-only 登录端点发 GET → 401 AUTH_REQUIRED")
    void anonymousGetLoginStill401() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("R1/I-02 + AC-AUTH-04：READER 对管理员规则覆盖的 PUT-only 端点发 GET → 403 FORBIDDEN（授权先于方法解析）")
    void readerWrongMethodStill403() throws Exception {
        testDataFactory.reader("rdr-mna", "Reader@123", "读者甲",
                com.example.library.domain.enums.ReaderStatus.ACTIVE);
        var session = authTestHelper.loginAs("rdr-mna", "Reader@123");
        mockMvc.perform(get("/api/auth/password").session(session.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("R1/I-02：ADMIN 对 PUT-only 端点发 GET → 405 METHOD_NOT_ALLOWED，统一错误结构、不含堆栈")
    void adminWrongMethodGets405() throws Exception {
        testDataFactory.admin("adm-mna", "Admin@12345");
        var session = authTestHelper.loginAs("adm-mna", "Admin@12345");
        mockMvc.perform(get("/api/auth/password").session(session.session()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.path").value("/api/auth/password"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("HttpRequestMethodNotSupportedException")
                        .doesNotContain("at com.example"));
    }

    @Test
    @DisplayName("R1/I-02：ADMIN 对 POST-only 登录端点发 GET → 405 METHOD_NOT_ALLOWED")
    void adminGetLoginGets405() throws Exception {
        testDataFactory.admin("adm-mna2", "Admin@12345");
        var session = authTestHelper.loginAs("adm-mna2", "Admin@12345");
        mockMvc.perform(get("/api/auth/login").session(session.session()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}