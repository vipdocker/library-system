package com.example.library.catalog;

import java.util.stream.Stream;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Category;
import com.example.library.domain.enums.CopyStatus;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-AUTH-04（catalog 模块精确授权用例）+ AC-SRCH-02（后端部分）。
 * 与 T01 的 AuthorizationMatrixTest（只读，allowed() 粗断言）互补：
 * 本测试用真实数据把 ADMIN 的放行细化为精确 2xx，READER 403 FORBIDDEN、匿名 401 AUTH_REQUIRED。
 * 非 GET 请求附带真实 CSRF Token，测授权而不是 CSRF。
 * 路径占位符 {categoryId}/{freeCategoryId}/{bookId}/{freeBookId}/{copyId} 在每次执行时解析为真实 id。
 */
class CatalogAuthorizationTest extends CatalogTestBase {

    private Category referencedCategory;
    private Category freeCategory;
    private Book book;
    private Book freeBook;
    private BookCopy copy;

    @BeforeEach
    void seedCatalog() {
        referencedCategory = testDataFactory.category("授权分类");
        freeCategory = testDataFactory.category("空闲分类");
        book = testDataFactory.book("9787115428028", "授权图书", "作者", referencedCategory.getId());
        freeBook = testDataFactory.book("9787115544834", "可删图书", "作者", null);
        copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
    }

    private static Stream<Arguments> rows(String method, String path, String body,
                                          int anon, int reader, int admin) {
        return Stream.of(
                Arguments.of(method, path, body, "ANONYMOUS", anon),
                Arguments.of(method, path, body, "READER", reader),
                Arguments.of(method, path, body, "ADMIN", admin));
    }

    static Stream<Arguments> matrix() {
        String categoryBody = "{\"name\":\"授权新分类\"}";
        String bookBody = "{\"isbn\":\"9780306406157\",\"title\":\"授权新书\",\"author\":\"作者\"}";
        return Stream.of(
                // 已登录可读（AC-SRCH-02）
                rows("GET", "/api/categories", null, 401, 200, 200),
                rows("GET", "/api/books", null, 401, 200, 200),
                rows("GET", "/api/books/{bookId}", null, 401, 200, 200),
                // 分类写操作
                rows("POST", "/api/categories", categoryBody, 401, 403, 201),
                rows("PUT", "/api/categories/{categoryId}", categoryBody, 401, 403, 200),
                rows("DELETE", "/api/categories/{freeCategoryId}", null, 401, 403, 204),
                // 图书写操作
                rows("POST", "/api/books", bookBody, 401, 403, 201),
                rows("PUT", "/api/books/{bookId}", bookBody, 401, 403, 200),
                rows("DELETE", "/api/books/{freeBookId}", null, 401, 403, 204),
                // 副本（读者不可见副本列表，AC-SRCH-02）
                rows("GET", "/api/books/{bookId}/copies", null, 401, 403, 200),
                rows("POST", "/api/books/{bookId}/copies", "{\"count\":1}", 401, 403, 201),
                rows("PATCH", "/api/copies/{copyId}", "{\"status\":\"UNAVAILABLE\"}", 401, 403, 200),
                rows("DELETE", "/api/copies/{copyId}", null, 401, 403, 204))
                .flatMap(s -> s);
    }

    @ParameterizedTest(name = "AC-AUTH-04 {0} {1} as {2} → {4}")
    @MethodSource("matrix")
    @DisplayName("AC-AUTH-04：catalog 授权矩阵（匿名 401 / READER 403 / ADMIN 精确 2xx）")
    void authorizationMatrix(String method, String pathTemplate, String body, String actor, int expected)
            throws Exception {
        String path = pathTemplate
                .replace("{categoryId}", String.valueOf(referencedCategory.getId()))
                .replace("{freeCategoryId}", String.valueOf(freeCategory.getId()))
                .replace("{bookId}", String.valueOf(book.getId()))
                .replace("{freeBookId}", String.valueOf(freeBook.getId()))
                .replace("{copyId}", String.valueOf(copy.getId()));
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
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
            case "READER" -> request.session(reader.session());
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
    @DisplayName("AC-SRCH-02：读者可以检索并查看图书详情与可借数量；副本列表 403；未登录 401")
    void readerCanSearchAndSeeAvailability() throws Exception {
        // 读者检索
        MvcResult search = mockMvc.perform(get("/api/books").param("q", "授权图书").session(reader.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andReturn();
        assertThat((Integer) JsonPath.read(body(search), "$.items[0].availableCopies")).isEqualTo(1);
        // 读者查看详情（含可借数量）
        mockMvc.perform(get("/api/books/" + book.getId()).session(reader.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCopies").value(1))
                .andExpect(jsonPath("$.availableCopies").value(1));
        // 读者看不到副本列表
        mockMvc.perform(get("/api/books/" + book.getId() + "/copies").session(reader.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 未登录
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/books/" + book.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-AUTH-04：READER 写操作被拒绝后数据完全不变")
    void readerWritesLeaveDataUnchanged() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        mockMvc.perform(post("/api/categories").session(reader.session())
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"读者分类\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/books/" + book.getId()).session(reader.session())
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/copies/" + copy.getId()).session(reader.session())
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM category", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM book_copy WHERE id = ?", String.class,
                copy.getId())).isEqualTo("AVAILABLE");
    }
}