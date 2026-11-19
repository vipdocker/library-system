package com.example.library.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-RDR-02：修改读者资料、停用/启用与账号 enabled 同步；
 * AC-AUTH-02（停用联动）：停用后用正确口令真实登录 → 401 AUTH_ACCOUNT_DISABLED，重新启用后恢复。
 * 借书/续借侧的 READER_DISABLED 拒绝由 T06（loan 模块）覆盖，本测试只覆盖读者与登录侧。
 */
class ReaderStatusTest extends ReaderTestBase {

    private String updateBody(String name, String status) {
        return """
                {"name":"%s","phone":"12345","email":"zhang@example.com","status":"%s"}""".formatted(name, status);
    }

    @Test
    @DisplayName("AC-RDR-02：修改读者资料 → 200，字段更新落库")
    void updateProfile() throws Exception {
        long id = readerA.reader().getId();
        asAdmin(put("/api/readers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("张三新", "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("张三新"))
                .andExpect(jsonPath("$.phone").value("12345"))
                .andExpect(jsonPath("$.email").value("zhang@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cardNo").value(readerA.reader().getCardNo()));
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM reader WHERE id = ?", String.class, id))
                .isEqualTo("张三新");
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM reader WHERE id = ?", String.class, id))
                .isEqualTo("12345");
    }

    @Test
    @DisplayName("AC-RDR-02 + AC-AUTH-02：停用 → 账号 enabled=0，正确口令登录被拒 401 AUTH_ACCOUNT_DISABLED；启用后恢复（端到端）")
    void disableBlocksLoginEndToEnd() throws Exception {
        long id = readerA.reader().getId();
        // 停用前可以登录（基类已真实登录成功）
        // 管理员停用读者
        asAdmin(put("/api/readers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("张三", "DISABLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reader WHERE id = ?", String.class, id))
                .isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM app_user WHERE id = ?", Long.class,
                readerA.user().getId())).isEqualTo(0);

        // 停用后用正确口令重新登录 → 401 AUTH_ACCOUNT_DISABLED（真实 login 接口，端到端）
        MvcResult denied = attemptLogin("reader-a", READER_PASSWORD);
        assertThat(denied.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body(denied), "$.code"))
                .isEqualTo("AUTH_ACCOUNT_DISABLED");
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body(denied), "$.message"))
                .isEqualTo(com.example.library.common.error.ErrorCode.AUTH_ACCOUNT_DISABLED.getDefaultMessage());

        // 已登录会话访问 /api/auth/me 也失效（T01 会话校验行为）
        mockMvc.perform(get("/api/auth/me").session(readerSessionA.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        // 重新启用 → enabled=1，登录恢复
        asAdmin(put("/api/readers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("张三", "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM app_user WHERE id = ?", Long.class,
                readerA.user().getId())).isEqualTo(1);
        MvcResult restored = attemptLogin("reader-a", READER_PASSWORD);
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-AUTH-02：停用账号用错误口令登录 → 401 AUTH_INVALID_CREDENTIALS（不泄露账号状态）")
    void disabledWithWrongPasswordDoesNotLeakState() throws Exception {
        asAdmin(put("/api/readers/" + readerA.reader().getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("张三", "DISABLED")))
                .andExpect(status().isOk());
        MvcResult result = attemptLogin("reader-a", "WrongPass123");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body(result), "$.code"))
                .isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("AC-RDR-02：非法 status → 400 + fieldErrors[status]，数据不变")
    void invalidStatusRejected() throws Exception {
        long id = readerA.reader().getId();
        asAdmin(put("/api/readers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("张三", "SUSPENDED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'status')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reader WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM app_user WHERE id = ?", Long.class,
                readerA.user().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-RDR-02：更新不存在的读者 → 404 READER_NOT_FOUND；字段非法 → 400")
    void updateMissingOrInvalid() throws Exception {
        asAdmin(put("/api/readers/999999").contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("不存在", "ACTIVE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
        asAdmin(put("/api/readers/" + readerA.reader().getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"张三","email":"bad-email","status":"ACTIVE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").isNotEmpty());
    }
}