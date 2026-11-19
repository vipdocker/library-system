package com.example.library.reader;

import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * AC-AUTH-04（reader 模块精确授权用例，写法与 T04 CatalogAuthorizationTest 一致）：
 * 匿名 401 AUTH_REQUIRED / READER 403 FORBIDDEN / ADMIN 精确 2xx；
 * /api/readers/me 例外：READER 200、ADMIN 403（design-v0.1 第 7 节矩阵）。
 * 非 GET 请求附带真实 CSRF Token，测授权而不是 CSRF。
 */
class ReaderAuthorizationTest extends ReaderTestBase {

    private static Stream<Arguments> rows(String method, String path, String body,
                                          int anon, int reader, int admin) {
        return Stream.of(
                Arguments.of(method, path, body, "ANONYMOUS", anon),
                Arguments.of(method, path, body, "READER", reader),
                Arguments.of(method, path, body, "ADMIN", admin));
    }

    static Stream<Arguments> matrix() {
        String createBody = """
                {"name":"授权读者","username":"auth-new","initialPassword":"AuthNew@123"}""";
        String updateBody = """
                {"name":"张三改","status":"ACTIVE"}""";
        String passwordBody = "{\"newPassword\":\"Reset@1234\"}";
        return Stream.of(
                rows("GET", "/api/readers", null, 401, 403, 200),
                rows("GET", "/api/readers/{readerId}", null, 401, 403, 200),
                rows("POST", "/api/readers", createBody, 401, 403, 201),
                rows("PUT", "/api/readers/{readerId}", updateBody, 401, 403, 200),
                rows("PUT", "/api/readers/{readerId}/password", passwordBody, 401, 403, 204),
                rows("DELETE", "/api/readers/{freeReaderId}", null, 401, 403, 204),
                // /me 例外行：仅 READER
                rows("GET", "/api/readers/me", null, 401, 200, 403))
                .flatMap(s -> s);
    }

    @ParameterizedTest(name = "AC-AUTH-04 {0} {1} as {2} → {4}")
    @MethodSource("matrix")
    @DisplayName("AC-AUTH-04：reader 授权矩阵（匿名 401 / READER 403 / ADMIN 精确 2xx；/me 仅 READER）")
    void authorizationMatrix(String method, String pathTemplate, String body, String actor, int expected)
            throws Exception {
        String path = pathTemplate
                .replace("{readerId}", String.valueOf(readerA.reader().getId()))
                .replace("{freeReaderId}", String.valueOf(readerB.reader().getId()));
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (!"GET".equals(method)) {
            String token = authTestHelper.freshCsrfToken();
            request.cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token);
        }
        switch (actor) {
            case "READER" -> request.session(readerSessionA.session());
            case "ADMIN" -> request.session(admin.session());
            default -> {
            }
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s %s as %s 期望 %d，body=%s", method, path, actor, expected,
                        result.getResponse().getContentAsString())
                .isEqualTo(expected);
        if (expected == 401) {
            jsonPath("$.code").value("AUTH_REQUIRED").match(result);
        } else if (expected == 403) {
            jsonPath("$.code").value("FORBIDDEN").match(result);
        }
    }

    @Test
    @DisplayName("AC-AUTH-04：READER 对读者写操作被拒绝后数据完全不变")
    void readerWritesLeaveDataUnchanged() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        asReaderA(post("/api/readers").cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"读者自建","username":"self-made","initialPassword":"SelfMade@123"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        asReaderA(put("/api/readers/" + readerA.reader().getId())
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"自行改名","status":"DISABLED"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        asReaderA(delete("/api/readers/" + readerB.reader().getId())
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM reader WHERE id = ?", String.class,
                readerA.reader().getId())).isEqualTo("张三");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reader WHERE id = ?", String.class,
                readerA.reader().getId())).isEqualTo("ACTIVE");
    }
}