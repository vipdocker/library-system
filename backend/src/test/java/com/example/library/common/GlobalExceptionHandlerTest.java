package com.example.library.common;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错误响应契约（design §8.1/8.2；DOD-08：500 不含堆栈/内部信息；DB_BUSY 503）。
 * 用 standalone MockMvc 直接验证 GlobalExceptionHandler，不引入额外控制器到共享上下文。
 */
class GlobalExceptionHandlerTest {

    /** 仅存在于本测试的探针控制器（standalone 注册，不进组件扫描）。 */
    @RestController
    static class ErrorProbeController {

        @GetMapping("/api/probe/boom")
        public void boom() {
            throw new IllegalStateException("secret-internal-detail");
        }

        @GetMapping("/api/probe/busy")
        public void busy() {
            throw new CannotAcquireLockException("lock timeout",
                    new SQLException("[SQLITE_BUSY] database is locked"));
        }

        @GetMapping("/api/probe/integrity")
        public void integrity() {
            throw new DataIntegrityViolationException("UNIQUE constraint failed: secret_table.col");
        }

        @GetMapping("/api/probe/limit")
        public void limit() {
            throw ApiException.withDetails(ErrorCode.LOAN_LIMIT_EXCEEDED, Map.of("limit", 5, "current", 5));
        }

        @PostMapping("/api/probe/validate")
        public void validate(@Valid @RequestBody ProbeBody body) {
        }

        @org.springframework.web.bind.annotation.PutMapping("/api/probe/put-only")
        public void putOnly() {
        }

        record ProbeBody(@NotBlank(message = "名称不能为空") String name) {
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(fixed))
                .build();
    }

    @Test
    @DisplayName("DOD-08：未预期异常 → 500 INTERNAL_ERROR，不含堆栈与内部信息；fieldErrors=[]、details={}")
    void internalErrorHidesInternals() throws Exception {
        mockMvc.perform(get("/api/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.path").value("/api/probe/boom"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-17T08:00:00Z"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-internal-detail")
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("at com.example"));
    }

    @Test
    @DisplayName("DOD-08：SQLITE_BUSY/锁超时 → 503 DB_BUSY（可重试）")
    void dbBusyMappedTo503() throws Exception {
        mockMvc.perform(get("/api/probe/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_BUSY"));
    }

    @Test
    @DisplayName("DOD-08：DataIntegrityViolation 兜底不泄露约束名/SQL")
    void integrityFallbackHidesDetails() throws Exception {
        mockMvc.perform(get("/api/probe/integrity"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("UNIQUE constraint")
                        .doesNotContain("secret_table"));
    }

    @Test
    @DisplayName("AC-LOAN-02：ApiException 携带 details（limit/current）原样输出")
    void apiExceptionDetails() throws Exception {
        mockMvc.perform(get("/api/probe/limit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.limit").value(5))
                .andExpect(jsonPath("$.details.current").value(5));
    }

    @Test
    @DisplayName("AC-BOOK-01：请求体校验错误 → 400 VALIDATION_FAILED + fieldErrors")
    void validationFieldErrors() throws Exception {
        mockMvc.perform(post("/api/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("名称不能为空"));
    }

    @Test
    @DisplayName("请求体非法 JSON → 400 VALIDATION_FAILED，不暴露解析细节")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/api/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("R1/I-02：对 PUT-only 端点发 GET → 405 METHOD_NOT_ALLOWED，统一错误结构、不含堆栈")
    void methodNotSupportedMappedTo405() throws Exception {
        mockMvc.perform(get("/api/probe/put-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.path").value("/api/probe/put-only"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-17T08:00:00Z"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0))
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("HttpRequestMethodNotSupportedException")
                        .doesNotContain("at com.example")
                        .doesNotContain("Supported methods"));
    }
}