package com.example.library.security;

import java.util.stream.Stream;

import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D2 开关验证（AC-AUTH-04 / AC-LOAN-05 的授权部分）：
 * lib.loan.reader-self-renew=true 时 READER 也能通过续借的 URL 授权（归属校验在服务层，T06）。
 * 默认 false 的 403 断言在 AuthorizationMatrixTest。
 */
@TestPropertySource(properties = "lib.loan.reader-self-renew=true")
class AuthorizationMatrixD2Test extends IntegrationTestBase {

    private LibraryUserPrincipal adminPrincipal;
    private LibraryUserPrincipal readerPrincipal;

    @BeforeEach
    void createActors() {
        testDataFactory.admin("d2-admin", "Admin@12345");
        testDataFactory.reader("d2-reader", "Reader@123", "D2读者", ReaderStatus.ACTIVE);
        adminPrincipal = testDataFactory.principal("d2-admin");
        readerPrincipal = testDataFactory.principal("d2-reader");
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("ANONYMOUS", 401, true),
                Arguments.of("READER", 0, false),
                Arguments.of("ADMIN", 0, false));
    }

    @ParameterizedTest(name = "D2=true POST /api/loans/1/renew as {0}")
    @MethodSource("cases")
    @DisplayName("AC-AUTH-04/AC-LOAN-05：D2=允许时读者可通过续借授权，匿名仍 401")
    void renewAllowedForReaderWhenSelfRenewEnabled(String actor, int expectedStatus, boolean exact) throws Exception {
        String token = authTestHelper.freshCsrfToken();
        MockHttpServletRequestBuilder request = post("/api/loans/1/renew")
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token);
        switch (actor) {
            case "READER" -> request.with(user(readerPrincipal));
            case "ADMIN" -> request.with(user(adminPrincipal));
            default -> {
            }
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        if (exact) {
            assertThat(actual).isEqualTo(expectedStatus);
        } else {
            // 业务端点由 T06 实现；此处只证明授权放行（当前无处理器 → 404）
            assertThat(actual).as("body=%s", result.getResponse().getContentAsString())
                    .isNotIn(401, 403);
            assertThat(actual).isLessThan(500);
        }
    }
}