package com.example.library.stats;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * AC-AUTH-04（stats 模块精确授权用例，写法与 T04/T05/T06 的 *AuthorizationTest 一致）：
 * /api/stats/**、/api/reports/** 仅 ADMIN——匿名 401 AUTH_REQUIRED / READER 403 FORBIDDEN / ADMIN 200
 * （design-v0.1 第 7 节授权矩阵“其余 /api/**”行）。全部 GET，无需 CSRF。
 */
class StatsAuthorizationTest extends StatsTestBase {

    private static Stream<Arguments> matrix() {
        return Stream.of(
                        "/api/stats/overview",
                        "/api/stats/loans",
                        "/api/stats/top-books",
                        "/api/reports/overdue")
                .flatMap(path -> Stream.of(
                        Arguments.of(path, "ANONYMOUS", 401),
                        Arguments.of(path, "READER", 403),
                        Arguments.of(path, "ADMIN", 200)));
    }

    @ParameterizedTest(name = "AC-AUTH-04 GET {0} as {1} → {2}")
    @MethodSource("matrix")
    @DisplayName("AC-AUTH-04：stats/reports 授权矩阵（匿名 401 / READER 403 / ADMIN 200）")
    void acAuth04_statsAuthorizationMatrix(String path, String actor, int expected) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        switch (actor) {
            case "READER" -> request.session(readerSessionA.session());
            case "ADMIN" -> request.session(admin.session());
            default -> {
            }
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("GET %s as %s 期望 %d，body=%s", path, actor, expected,
                        result.getResponse().getContentAsString())
                .isEqualTo(expected);
        if (expected == 401) {
            jsonPath("$.code").value("AUTH_REQUIRED").match(result);
        } else if (expected == 403) {
            jsonPath("$.code").value("FORBIDDEN").match(result);
        }
    }
}