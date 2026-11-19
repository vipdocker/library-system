package com.example.library.loan;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.library.domain.entity.AppUser;
import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.AuthTestHelper.AuthSession;
import com.example.library.support.IntegrationTestBase;
import com.example.library.support.TestDataFactory.ReaderFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T06 私有测试夹具（loan 测试包内，不属于共享 support；写法与 T04/T05 的 TestBase 一致）。
 * 每个测试前：清表（基类）→ 创建管理员与两个读者 → 真实登录取得会话与 CSRF Token。
 * 请求辅助统一附带会话 + Cookie + X-XSRF-TOKEN，测业务而不是 CSRF。
 */
abstract class LoanTestBase extends IntegrationTestBase {

    protected static final String ADMIN_PASSWORD = "Admin@12345";
    protected static final String READER_PASSWORD = "Reader@123";

    private static final AtomicInteger ISBN_SEQ = new AtomicInteger();

    protected AppUser adminUser;
    protected ReaderFixture readerA;
    protected ReaderFixture readerB;
    protected AuthSession admin;
    protected AuthSession readerSessionA;
    protected AuthSession readerSessionB;

    @BeforeEach
    void loginActors() throws Exception {
        adminUser = testDataFactory.admin("loan-admin", ADMIN_PASSWORD);
        readerA = testDataFactory.reader("loan-reader-a", READER_PASSWORD, "张三", ReaderStatus.ACTIVE);
        readerB = testDataFactory.reader("loan-reader-b", READER_PASSWORD, "李四", ReaderStatus.ACTIVE);
        admin = authTestHelper.loginAs("loan-admin", ADMIN_PASSWORD);
        readerSessionA = authTestHelper.loginAs("loan-reader-a", READER_PASSWORD);
        readerSessionB = authTestHelper.loginAs("loan-reader-b", READER_PASSWORD);
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

    protected ResultActions anon(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    // ---- 数据构造 ----

    protected Book newBook(String title) {
        return testDataFactory.book(isbn13(String.format("9787000000%02d", ISBN_SEQ.incrementAndGet())),
                title, "作者" + title, null);
    }

    protected BookCopy newCopy(Book book) {
        return testDataFactory.copy(book, CopyStatus.AVAILABLE);
    }

    /** 通过真实接口借书并断言 201。 */
    protected MvcResult borrowOk(String cardNo, String barcode) throws Exception {
        return asAdmin(borrowRequest(cardNo, barcode)).andExpect(status().isCreated()).andReturn();
    }

    protected MockHttpServletRequestBuilder borrowRequest(String cardNo, String barcode) {
        return post("/api/loans").contentType(MediaType.APPLICATION_JSON)
                .content("{\"readerCardNo\":\"%s\",\"copyBarcode\":\"%s\"}".formatted(cardNo, barcode));
    }

    /** 直接在库中构造在借借阅（副本同步置为 BORROWED）。 */
    protected Loan activeLoan(BookCopy copy, ReaderFixture reader, LocalDate borrowDate, LocalDate dueDate) {
        return testDataFactory.activeLoan(copy, reader.reader(), borrowDate, dueDate, adminUser.getId());
    }

    protected LocalDate today() {
        return LocalDate.now(mutableClock);
    }

    protected long loanCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class);
    }

    protected String copyStatus(long copyId) {
        return jdbcTemplate.queryForObject("SELECT status FROM book_copy WHERE id = ?", String.class, copyId);
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected static long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    /** 由 12 位前缀计算合法 ISBN-13 校验位（与 T04 夹具一致）。 */
    protected static String isbn13(String prefix12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (prefix12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return prefix12 + ((10 - sum % 10) % 10);
    }
}