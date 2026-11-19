package com.example.library.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.library.support.TestDataFactory;
import com.example.library.support.TestDatabase;
import com.example.library.support.TestSupportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-AUTH-01（Cookie 属性）/ AC-AUTH-03（401 不重定向）在真实 Tomcat（RANDOM_PORT）上验证。
 * 使用临时数据库；不占用 18095。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
class SessionCookieTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newHttpClient(); // 默认不跟随重定向

    @BeforeEach
    void clean() {
        TestDatabase.cleanAllTables(jdbcTemplate);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("AC-AUTH-01：真实服务器上登录设置 JSESSIONID（HttpOnly、SameSite=Lax），/me 可用；csrf 探针 204")
    void sessionCookieAttributesOnRealServer() throws Exception {
        testDataFactory.admin("cookie-admin", "Admin@12345");

        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(URI.create(url("/api/auth/csrf"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(204);
        String xsrf = csrf.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
        Matcher matcher = Pattern.compile("XSRF-TOKEN=([^;]+)").matcher(xsrf);
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(1);

        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(url("/api/auth/login")))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + token)
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"cookie-admin\",\"password\":\"Admin@12345\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"username\":\"cookie-admin\"").contains("\"role\":\"ADMIN\"");
        List<String> cookies = login.headers().allValues("Set-Cookie");
        String sessionCookie = cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(sessionCookie).containsIgnoringCase("HttpOnly");
        assertThat(sessionCookie).containsIgnoringCase("SameSite=Lax");
        Matcher sessionMatcher = Pattern.compile("JSESSIONID=([^;]+)").matcher(sessionCookie);
        assertThat(sessionMatcher.find()).isTrue();
        String sessionId = sessionMatcher.group(1);

        HttpResponse<String> me = client.send(HttpRequest.newBuilder(URI.create(url("/api/auth/me")))
                        .header("Cookie", "JSESSIONID=" + sessionId)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"username\":\"cookie-admin\"");
    }

    @Test
    @DisplayName("AC-AUTH-03：真实服务器上未登录访问 /api/books 返回 401 JSON AUTH_REQUIRED，无 302/Location")
    void unauthorizedIsJsonNotRedirect() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/books"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).contains("\"code\":\"AUTH_REQUIRED\"");
    }
}