package com.example.library.reader;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-RDR-03：删除读者前检查借阅历史；无历史时档案与账号一并删除；
 * 在借 → 409 READER_HAS_ACTIVE_LOANS；有历史 → 409 READER_HAS_LOAN_HISTORY；失败不改变数据。
 */
class ReaderDeleteTest extends ReaderTestBase {

    @Test
    @DisplayName("AC-RDR-03：删除无借阅历史的读者 → 204，账号一并删除，原口令不能再登录")
    void deleteWithoutHistory() throws Exception {
        long id = readerB.reader().getId();
        long userId = readerB.user().getId();
        asAdmin(delete("/api/readers/" + id)).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader WHERE id = ?", Long.class, id))
                .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Long.class, userId))
                .isEqualTo(0);
        MvcResult login = attemptLogin("reader-b", READER_PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body(login), "$.code"))
                .isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("AC-RDR-03：读者有在借记录 → 409 READER_HAS_ACTIVE_LOANS，数据不变")
    void deleteWithActiveLoan() throws Exception {
        Book book = testDataFactory.book("9787115428028", "在借图书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        testDataFactory.activeLoan(copy, readerA.reader(), today().minusDays(5), today().plusDays(25),
                adminUser.getId());
        long id = readerA.reader().getId();
        asAdmin(delete("/api/readers/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_HAS_ACTIVE_LOANS"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader WHERE id = ?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Long.class,
                readerA.user().getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-RDR-03：读者只有已归还的历史借阅 → 409 READER_HAS_LOAN_HISTORY，数据不变")
    void deleteWithLoanHistory() throws Exception {
        Book book = testDataFactory.book("9787115428028", "历史图书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        testDataFactory.returnedLoan(copy, readerA.reader(), today().minusDays(40), today().minusDays(10),
                today().minusDays(12), adminUser.getId());
        long id = readerA.reader().getId();
        asAdmin(delete("/api/readers/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_HAS_LOAN_HISTORY"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader WHERE id = ?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Long.class,
                readerA.user().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-RDR-03：删除不存在的读者 → 404 READER_NOT_FOUND")
    void deleteMissing() throws Exception {
        asAdmin(delete("/api/readers/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
    }
}