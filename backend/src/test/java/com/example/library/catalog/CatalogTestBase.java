package com.example.library.catalog;

import com.example.library.domain.entity.AppUser;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.AuthTestHelper.AuthSession;
import com.example.library.support.IntegrationTestBase;
import com.example.library.support.TestDataFactory.ReaderFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T04 私有测试夹具（catalog 测试包内，不属于共享 support）。
 * 每个测试前：清表（基类）→ 创建管理员与读者 → 真实登录取得会话与 CSRF Token。
 * 请求辅助统一附带会话 + Cookie + X-XSRF-TOKEN，测业务而不是 CSRF。
 */
abstract class CatalogTestBase extends IntegrationTestBase {

    protected static final String ADMIN_PASSWORD = "Admin@12345";
    protected static final String READER_PASSWORD = "Reader@123";

    protected AppUser adminUser;
    protected ReaderFixture readerFixture;
    protected AuthSession admin;
    protected AuthSession reader;

    @BeforeEach
    void loginActors() throws Exception {
        adminUser = testDataFactory.admin("catalog-admin", ADMIN_PASSWORD);
        readerFixture = testDataFactory.reader("catalog-reader", READER_PASSWORD, "目录读者", ReaderStatus.ACTIVE);
        admin = authTestHelper.loginAs("catalog-admin", ADMIN_PASSWORD);
        reader = authTestHelper.loginAs("catalog-reader", READER_PASSWORD);
    }

    protected ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(admin.session()).cookie(admin.cookies())
                .header("X-XSRF-TOKEN", admin.csrfToken()));
    }

    protected ResultActions asReader(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(reader.session()).cookie(reader.cookies())
                .header("X-XSRF-TOKEN", reader.csrfToken()));
    }

    protected ResultActions anon(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected static long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    /** 由 12 位前缀计算合法 ISBN-13 校验位，构造任意数量的合法 ISBN。 */
    protected static String isbn13(String prefix12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (prefix12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return prefix12 + ((10 - sum % 10) % 10);
    }
}