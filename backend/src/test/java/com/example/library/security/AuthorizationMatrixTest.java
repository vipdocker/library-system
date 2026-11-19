package com.example.library.security;

import java.util.function.IntPredicate;
import java.util.stream.Stream;

import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 授权矩阵参数化框架（AC-AUTH-03 / AC-AUTH-04；design-v0.1 第 7 节逐行对应）。
 * T01 建立框架并覆盖 auth 部分与全部 URL 规则的 anon/READER/ADMIN 三态；
 * T04–T07 在各自测试包追加 *AuthorizationTest，用真实端点把 allowed() 细化为精确 2xx 断言。
 *
 * 约定：
 * - 非 GET 请求一律附带真实 CSRF Token（来自 GET /api/auth/csrf），以便测授权而不是 CSRF；
 *   CSRF 负向用例在 AuthControllerTest（AC-AUTH-06，不用 csrf() 后处理器）。
 * - allowed()：断言不是 401/403 且 <500。T01 阶段业务端点尚无处理器（404），
 *   只证明安全规则放行；后续任务实现后应改为精确状态断言。
 * - 401 行同时断言 code=AUTH_REQUIRED；403 行断言 code=FORBIDDEN。
 */
class AuthorizationMatrixTest extends IntegrationTestBase {

    enum Actor { ANONYMOUS, READER, ADMIN }

    record Expected(String label, IntPredicate test) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static Expected status(int expected) {
        return new Expected("=" + expected, actual -> actual == expected);
    }

    private static Expected allowed() {
        return new Expected("allowed(非401/403)", actual -> actual != 401 && actual != 403 && actual < 500);
    }

    private static Expected denied() {
        return new Expected("denied(401/403/404)", actual -> actual == 401 || actual == 403 || actual == 404);
    }

    private LibraryUserPrincipal adminPrincipal;
    private LibraryUserPrincipal readerPrincipal;

    @BeforeEach
    void createActors() {
        testDataFactory.admin("matrix-admin", "Admin@12345");
        testDataFactory.reader("matrix-reader", "Reader@123", "矩阵读者", ReaderStatus.ACTIVE);
        adminPrincipal = testDataFactory.principal("matrix-admin");
        readerPrincipal = testDataFactory.principal("matrix-reader");
    }

    private static Stream<Arguments> rows(String method, String path, Expected anon, Expected reader, Expected admin) {
        return Stream.of(
                Arguments.of(method, path, Actor.ANONYMOUS, anon),
                Arguments.of(method, path, Actor.READER, reader),
                Arguments.of(method, path, Actor.ADMIN, admin));
    }

    /** 其余 /api/**：匿名 401、READER 403、ADMIN 放行。 */
    private static Stream<Arguments> adminOnly(String method, String path) {
        return rows(method, path, status(401), status(403), allowed());
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                // auth（T01 已实现，精确断言）
                rows("GET", "/api/auth/csrf", status(204), status(204), status(204)),
                rows("POST", "/api/auth/login", status(200), status(200), status(200)),
                rows("GET", "/api/auth/me", status(401), status(200), status(200)),
                rows("POST", "/api/auth/logout", status(401), status(204), status(204)),
                // D4①：规则已预置，接口由 T14 实现（当前无处理器 → 404 证明放行）
                rows("PUT", "/api/auth/password", status(401), allowed(), allowed()),
                // 已登录可读
                rows("GET", "/api/categories", status(401), allowed(), allowed()),
                rows("GET", "/api/books", status(401), allowed(), allowed()),
                rows("GET", "/api/books/1", status(401), allowed(), allowed()),
                // 仅 READER 本人（ADMIN 403）
                rows("GET", "/api/readers/me", status(401), allowed(), status(403)),
                rows("GET", "/api/loans/me", status(401), allowed(), status(403)),
                // D2=false：续借仅 ADMIN
                rows("POST", "/api/loans/1/renew", status(401), status(403), allowed()),
                // 分类/图书/副本写操作与副本列表
                adminOnly("POST", "/api/categories"),
                adminOnly("PUT", "/api/categories/1"),
                adminOnly("DELETE", "/api/categories/1"),
                adminOnly("POST", "/api/books"),
                adminOnly("PUT", "/api/books/1"),
                adminOnly("DELETE", "/api/books/1"),
                adminOnly("GET", "/api/books/1/copies"),
                adminOnly("POST", "/api/books/1/copies"),
                adminOnly("PATCH", "/api/copies/1"),
                adminOnly("DELETE", "/api/copies/1"),
                // 读者管理（/me 除外）
                adminOnly("GET", "/api/readers"),
                adminOnly("POST", "/api/readers"),
                adminOnly("GET", "/api/readers/1"),
                adminOnly("PUT", "/api/readers/1"),
                adminOnly("DELETE", "/api/readers/1"),
                adminOnly("PUT", "/api/readers/1/password"),
                // 借阅管理
                adminOnly("GET", "/api/loans"),
                adminOnly("POST", "/api/loans"),
                adminOnly("POST", "/api/loans/1/return"),
                adminOnly("POST", "/api/loans/return-by-barcode"),
                // 统计与报表
                adminOnly("GET", "/api/stats/overview"),
                adminOnly("GET", "/api/stats/loans"),
                adminOnly("GET", "/api/stats/top-books"),
                adminOnly("GET", "/api/reports/overdue"),
                // 非 /api/**：拒绝
                rows("GET", "/definitely-not-api", denied(), denied(), denied()))
                .flatMap(s -> s);
    }

    @ParameterizedTest(name = "AC-AUTH-03/04 {0} {1} as {2} → {3}")
    @MethodSource("matrix")
    @DisplayName("AC-AUTH-03/04 授权矩阵（design §7 全行 × 匿名/READER/ADMIN）")
    void authorizationMatrix(String method, String path, Actor actor, Expected expected) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (!"GET".equals(method)) {
            String token = authTestHelper.freshCsrfToken();
            request.cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token);
        }
        if ("/api/auth/login".equals(path)) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"matrix-admin\",\"password\":\"Admin@12345\"}");
        }
        switch (actor) {
            case READER -> request.with(user(readerPrincipal));
            case ADMIN -> request.with(user(adminPrincipal));
            case ANONYMOUS -> {
            }
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        assertThat(expected.test().test(actual))
                .as("%s %s as %s 期望 %s，实际 %d，body=%s", method, path, actor, expected.label(), actual,
                        result.getResponse().getContentAsString())
                .isTrue();
        if (actual == 401) {
            jsonPath("$.code").value("AUTH_REQUIRED").match(result);
        } else if (actual == 403) {
            jsonPath("$.code").value("FORBIDDEN").match(result);
        }
    }
}