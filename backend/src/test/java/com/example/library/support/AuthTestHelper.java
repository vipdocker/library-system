package com.example.library.support;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录与 CSRF 请求辅助（测试支撑，共享契约：T01 之后只读）。
 * 走真实接口：GET /api/auth/csrf 拿 Token → POST /api/auth/login（带 Cookie + 头）→ 会话。
 * 后续请求：.session(s.session()).cookie(s.cookies()).header("X-XSRF-TOKEN", s.csrfToken())。
 * 负向 CSRF 用例请故意不带头或带错误值（AC-AUTH-06 要求不用 csrf() 后处理器）。
 */
public class AuthTestHelper {

    /** 已登录会话：MockHttpSession + 登录轮换后的 CSRF Token。 */
    public record AuthSession(MockHttpSession session, String csrfToken) {

        public Cookie[] cookies() {
            return new Cookie[]{new Cookie("XSRF-TOKEN", csrfToken)};
        }
    }

    private final MockMvc mockMvc;

    public AuthTestHelper(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** 匿名获取 CSRF Token（同时验证 csrf 探针 204）。 */
    public String freshCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN Cookie 必须下发").isNotNull();
        return cookie.getValue();
    }

    /** 真实登录，返回会话与轮换后的 Token。 */
    public AuthSession loginAs(String username, String password) throws Exception {
        String token = freshCsrfToken();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("登录必须建立会话").isNotNull();
        Cookie rotated = result.getResponse().getCookie("XSRF-TOKEN");
        String effectiveToken = rotated != null ? rotated.getValue() : token;
        return new AuthSession(session, effectiveToken);
    }
}