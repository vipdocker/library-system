package com.example.library.loan;

import java.util.stream.Stream;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * AC-AUTH-04（loan 模块精确授权用例，写法与 T04/T05 的 *AuthorizationTest 一致）：
 * 匿名 401 AUTH_REQUIRED / READER 403 FORBIDDEN / ADMIN 精确 2xx；
 * /api/loans/me 例外：READER 200、ADMIN 403；D2=false 时续借 READER 403（design-v0.1 第 7 节）。
 * 非 GET 请求附带真实 CSRF Token，测授权而不是 CSRF。
 */
class LoanAuthorizationTest extends LoanTestBase {

    private BookCopy freeCopy;
    private BookCopy activeCopy;
    private Loan activeLoan;

    @BeforeEach
    void createLoanData() {
        Book book1 = newBook("授权在借书");
        activeCopy = newCopy(book1);
        activeLoan = activeLoan(activeCopy, readerA, today().minusDays(10), today().plusDays(20));
        // 借书成功行用另一书目的空闲副本，避免触发 D4② 同书目规则
        freeCopy = newCopy(newBook("授权空闲书"));
    }

    private static Stream<Arguments> rows(String method, String path, String body,
                                          int anon, int reader, int admin) {
        return Stream.of(
                Arguments.of(method, path, body, "ANONYMOUS", anon),
                Arguments.of(method, path, body, "READER", reader),
                Arguments.of(method, path, body, "ADMIN", admin));
    }

    private static Stream<Arguments> matrix() {
        String borrowBody = "{\"readerCardNo\":\"{cardNo}\",\"copyBarcode\":\"{freeBarcode}\"}";
        String returnByBarcodeBody = "{\"copyBarcode\":\"{activeBarcode}\"}";
        return Stream.of(
                rows("GET", "/api/loans", null, 401, 403, 200),
                rows("POST", "/api/loans", borrowBody, 401, 403, 201),
                rows("POST", "/api/loans/{loanId}/return", null, 401, 403, 200),
                rows("POST", "/api/loans/return-by-barcode", returnByBarcodeBody, 401, 403, 200),
                rows("POST", "/api/loans/{loanId}/renew", null, 401, 403, 200),
                // /me 例外行：仅 READER
                rows("GET", "/api/loans/me", null, 401, 200, 403))
                .flatMap(s -> s);
    }

    @ParameterizedTest(name = "AC-AUTH-04 {0} {1} as {2} → {4}")
    @MethodSource("matrix")
    @DisplayName("AC-AUTH-04：loan 授权矩阵（匿名 401 / READER 403 / ADMIN 精确 2xx；/me 仅 READER；D2=false 续借仅 ADMIN）")
    void authorizationMatrix(String method, String pathTemplate, String bodyTemplate, String actor, int expected)
            throws Exception {
        String path = pathTemplate.replace("{loanId}", String.valueOf(activeLoan.getId()));
        String body = bodyTemplate == null ? null : bodyTemplate
                .replace("{cardNo}", readerA.reader().getCardNo())
                .replace("{freeBarcode}", freeCopy.getBarcode())
                .replace("{activeBarcode}", activeCopy.getBarcode());
        MockHttpServletRequestBuilder request = "GET".equals(method) ? get(path) : post(path);
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
    @DisplayName("AC-AUTH-04：READER 对借/还/续借被拒绝后数据完全不变")
    void readerWritesLeaveDataUnchanged() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        asReaderA(post("/api/loans").cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"readerCardNo\":\"%s\",\"copyBarcode\":\"%s\"}"
                                .formatted(readerA.reader().getCardNo(), freeCopy.getBarcode())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        asReaderA(post("/api/loans/" + activeLoan.getId() + "/return")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        asReaderA(post("/api/loans/return-by-barcode")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"copyBarcode\":\"%s\"}".formatted(activeCopy.getBarcode())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        asReaderA(post("/api/loans/" + activeLoan.getId() + "/renew")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(loanCount()).isEqualTo(1);
        assertThat(copyStatus(activeCopy.getId())).isEqualTo("BORROWED");
        assertThat(copyStatus(freeCopy.getId())).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                activeLoan.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM loan WHERE id = ?", String.class,
                activeLoan.getId())).isEqualTo(today().plusDays(20).toString());
        assertThat(jdbcTemplate.queryForObject("SELECT returned_at FROM loan WHERE id = ?", Long.class,
                activeLoan.getId())).isNull();
    }
}