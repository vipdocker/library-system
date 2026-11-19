package com.example.library.reader;

import java.time.LocalDate;

import com.example.library.domain.entity.AppUser;
import com.example.library.support.AuthTestHelper.AuthSession;
import com.example.library.support.IntegrationTestBase;
import com.example.library.support.TestDataFactory.ReaderFixture;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T05 私有测试夹具（reader 测试包内，不属于共享 support）。
 * 每个测试前：清表（基类）→ 创建管理员与两个读者 → 真实登录取得会话与 CSRF Token。
 * 请求辅助统一附带会话 + Cookie + X-XSRF-TOKEN，测业务而不是 CSRF（写法与 T04 CatalogTestBase 一致）。
 */
abstract class ReaderTestBase extends IntegrationTestBase {

    protected static final String ADMIN_PASSWORD = "Admin@12345";
    protected static final String READER_PASSWORD = "Reader@123";

    protected AppUser adminUser;
    protected ReaderFixture readerA;
    protected ReaderFixture readerB;
    protected AuthSession admin;
    protected AuthSession readerSessionA;
    protected AuthSession readerSessionB;

    @BeforeEach
    void loginActors() throws Exception {
        adminUser = testDataFactory.admin("reader-admin", ADMIN_PASSWORD);
        readerA = testDataFactory.reader("reader-a", READER_PASSWORD, "张三",
                com.example.library.domain.enums.ReaderStatus.ACTIVE);
        readerB = testDataFactory.reader("reader-b", READER_PASSWORD, "李四",
                com.example.library.domain.enums.ReaderStatus.ACTIVE);
        admin = authTestHelper.loginAs("reader-admin", ADMIN_PASSWORD);
        readerSessionA = authTestHelper.loginAs("reader-a", READER_PASSWORD);
        readerSessionB = authTestHelper.loginAs("reader-b", READER_PASSWORD);
    }

    protected ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(admin.session()).cookie(admin.cookies())
                .header("X-XSRF-TOKEN", admin.csrfToken()));
    }

    protected ResultActions asReaderA(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(readerSessionA.session()).cookie(readerSessionA.cookies())
                .header("X-XSRF-TOKEN", readerSessionA.csrfToken()));
    }

    protected ResultActions asReaderB(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(readerSessionB.session()).cookie(readerSessionB.cookies())
                .header("X-XSRF-TOKEN", readerSessionB.csrfToken()));
    }

    /** 真实登录尝试（带 CSRF），不预设结果，供停用/重置口令的负向断言使用。 */
    protected MvcResult attemptLogin(String username, String password) throws Exception {
        String token = authTestHelper.freshCsrfToken();
        return mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
    }

    protected LocalDate today() {
        return LocalDate.now(mutableClock);
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected static long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }
}