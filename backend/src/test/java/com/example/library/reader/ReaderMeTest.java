package com.example.library.reader;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-RDR-06：GET /api/readers/me 返回本人卡号、姓名、联系方式、在借数与逾期数。
 * AC-AUTH-05（/me 部分）：只返回本人记录，readerId 参数被忽略（防 IDOR）；
 * READER 账号缺少档案 → 404 READER_PROFILE_NOT_FOUND；ADMIN 403、匿名 401。
 */
class ReaderMeTest extends ReaderTestBase {

    @BeforeEach
    void seedLoansAndProfile() throws Exception {
        Book book = testDataFactory.book("9787115428028", "我的图书", "作者", null);
        BookCopy copy1 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy copy2 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy copy3 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        // reader-a：1 条在借 + 1 条逾期；reader-b：1 条在借（数据不同，验证隔离）
        testDataFactory.activeLoan(copy1, readerA.reader(), today().minusDays(5), today().plusDays(10),
                adminUser.getId());
        testDataFactory.activeLoan(copy2, readerA.reader(), today().minusDays(40), today().minusDays(1),
                adminUser.getId());
        testDataFactory.activeLoan(copy3, readerB.reader(), today().minusDays(2), today().plusDays(28),
                adminUser.getId());
        // 给 reader-a 设置联系方式
        asAdmin(put("/api/readers/" + readerA.reader().getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"张三","phone":"13800001111","email":"zhang@example.com","status":"ACTIVE"}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-RDR-06：/me 返回本人卡号、姓名、联系方式、在借数与逾期数")
    void meReturnsOwnProfile() throws Exception {
        asReaderA(get("/api/readers/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(readerA.reader().getId()))
                .andExpect(jsonPath("$.cardNo").value(readerA.reader().getCardNo()))
                .andExpect(jsonPath("$.name").value("张三"))
                .andExpect(jsonPath("$.phone").value("13800001111"))
                .andExpect(jsonPath("$.email").value("zhang@example.com"))
                .andExpect(jsonPath("$.username").value("reader-a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.activeLoanCount").value(2))
                .andExpect(jsonPath("$.overdueLoanCount").value(1));
    }

    @Test
    @DisplayName("AC-AUTH-05：两个读者各自 /me 只返回本人记录（数据隔离）")
    void meIsolationBetweenReaders() throws Exception {
        asReaderB(get("/api/readers/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(readerB.reader().getId()))
                .andExpect(jsonPath("$.cardNo").value(readerB.reader().getCardNo()))
                .andExpect(jsonPath("$.name").value("李四"))
                .andExpect(jsonPath("$.username").value("reader-b"))
                .andExpect(jsonPath("$.activeLoanCount").value(1))
                .andExpect(jsonPath("$.overdueLoanCount").value(0));
    }

    @Test
    @DisplayName("AC-AUTH-05：/me 传入他人 readerId 参数被忽略，仍只返回本人数据")
    void meIgnoresReaderIdParam() throws Exception {
        asReaderA(get("/api/readers/me").param("readerId", String.valueOf(readerB.reader().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(readerA.reader().getId()))
                .andExpect(jsonPath("$.cardNo").value(readerA.reader().getCardNo()));
    }

    @Test
    @DisplayName("AC-RDR-06：READER 账号缺少读者档案 → 404 READER_PROFILE_NOT_FOUND（数据异常兜底）")
    void meWithoutProfile() throws Exception {
        testDataFactory.user("orphan-reader", "Orphan@123", Role.READER, true);
        var session = authTestHelper.loginAs("orphan-reader", "Orphan@123");
        mockMvc.perform(get("/api/readers/me").session(session.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_PROFILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-AUTH-04：ADMIN 访问 /api/readers/me → 403 FORBIDDEN；匿名 → 401 AUTH_REQUIRED")
    void meRoleRules() throws Exception {
        asAdmin(get("/api/readers/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/readers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}