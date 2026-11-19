package com.example.library.stats;

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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T07 私有测试夹具（stats 测试包内，不属于共享 support；写法与 T04/T05/T06 的 TestBase 一致）。
 * 每个测试前：清表 + 重置 MutableClock（基类，默认 today=2026-11-17，Asia/Shanghai）
 * → 创建管理员与读者 → 真实登录取得会话与 CSRF Token。
 */
abstract class StatsTestBase extends IntegrationTestBase {

    protected static final String ADMIN_PASSWORD = "Admin@12345";
    protected static final String READER_PASSWORD = "Reader@123";

    private static final AtomicInteger ISBN_SEQ = new AtomicInteger();

    protected AppUser adminUser;
    protected ReaderFixture readerA;
    protected AuthSession admin;
    protected AuthSession readerSessionA;

    @BeforeEach
    void loginActors() throws Exception {
        adminUser = testDataFactory.admin("stats-admin", ADMIN_PASSWORD);
        readerA = testDataFactory.reader("stats-reader-a", READER_PASSWORD, "统计读者甲", ReaderStatus.ACTIVE);
        admin = authTestHelper.loginAs("stats-admin", ADMIN_PASSWORD);
        readerSessionA = authTestHelper.loginAs("stats-reader-a", READER_PASSWORD);
    }

    protected ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(admin.session()).cookie(admin.cookies())
                .header("X-XSRF-TOKEN", admin.csrfToken()));
    }

    protected ResultActions asReaderA(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(readerSessionA.session()).cookie(readerSessionA.cookies())
                .header("X-XSRF-TOKEN", readerSessionA.csrfToken()));
    }

    protected ResultActions anon(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    // ---- 数据构造 ----

    protected Book newBook(String title) {
        return testDataFactory.book(isbn13(String.format("9787200000%02d", ISBN_SEQ.incrementAndGet())),
                title, "作者·" + title, null);
    }

    protected BookCopy newCopy(Book book) {
        return testDataFactory.copy(book, CopyStatus.AVAILABLE);
    }

    protected BookCopy unavailableCopy(Book book) {
        return testDataFactory.copy(book, CopyStatus.UNAVAILABLE);
    }

    /** 在借借阅（副本同步置为 BORROWED）。 */
    protected Loan activeLoan(BookCopy copy, ReaderFixture reader, LocalDate borrowDate, LocalDate dueDate) {
        return testDataFactory.activeLoan(copy, reader.reader(), borrowDate, dueDate, adminUser.getId());
    }

    /** 已归还的历史借阅（副本保持 AVAILABLE）。 */
    protected Loan returnedLoan(BookCopy copy, ReaderFixture reader, LocalDate borrowDate, LocalDate dueDate,
                                LocalDate returnDate) {
        return testDataFactory.returnedLoan(copy, reader.reader(), borrowDate, dueDate, returnDate,
                adminUser.getId());
    }

    protected LocalDate today() {
        return LocalDate.now(mutableClock);
    }

    /** 由 12 位前缀计算合法 ISBN-13 校验位（与 T04/T06 夹具一致）。 */
    protected static String isbn13(String prefix12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (prefix12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return prefix12 + ((10 - sum % 10) % 10);
    }
}