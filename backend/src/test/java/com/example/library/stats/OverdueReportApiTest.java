package com.example.library.stats;

import java.time.Instant;
import java.time.LocalDate;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-STAT-04：GET /api/reports/overdue 只列未还且 due_date < today（应还日当天不出现），
 * 含读者/书/条码/应还日/逾期天数，默认 overdueDays 降序；分页与排序白名单；时区边界。
 * today=2026-11-17（MutableClock 默认，Asia/Shanghai）。
 */
class OverdueReportApiTest extends StatsTestBase {

    private ReaderFixture readerB;
    private Book book1;
    private BookCopy copy1;
    private Loan overdue10; // due today-10 → 10 天
    private Loan overdue3;  // due today-3 → 3 天
    private Loan dueToday;  // 应还日=today → 不出现

    @BeforeEach
    void seedOverdueData() {
        readerB = testDataFactory.reader("stats-reader-b", READER_PASSWORD, "统计读者乙", ReaderStatus.ACTIVE);
        jdbcTemplate.update("UPDATE reader SET phone = ? WHERE id = ?", "13800000000",
                readerB.reader().getId());
        book1 = newBook("逾期书1");
        copy1 = newCopy(book1);
        overdue10 = activeLoan(copy1, readerA, today().minusDays(40), today().minusDays(10));
        overdue3 = activeLoan(newCopy(newBook("逾期书3")), readerB, today().minusDays(33), today().minusDays(3));
        dueToday = activeLoan(newCopy(newBook("到期书")), readerA, today().minusDays(30), today());
        // 未到期
        activeLoan(newCopy(newBook("未到期书")), readerA, today().minusDays(1), today().plusDays(29));
        // 已归还（即使曾逾期也不出现）
        returnedLoan(newCopy(newBook("已还书")), readerB, today().minusDays(50), today().minusDays(20),
                today().minusDays(1));
    }

    @Test
    @DisplayName("AC-STAT-04：只列未还且已过应还日的借阅，默认按逾期天数降序，字段完整精确")
    void acStat04_onlyOverdueDefaultSortDesc() throws Exception {
        asAdmin(get("/api/reports/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].loanId").value(overdue10.getId()))
                .andExpect(jsonPath("$.items[0].reader.id").value(readerA.reader().getId()))
                .andExpect(jsonPath("$.items[0].reader.cardNo").value(readerA.reader().getCardNo()))
                .andExpect(jsonPath("$.items[0].reader.name").value("统计读者甲"))
                .andExpect(jsonPath("$.items[0].reader.phone").isEmpty())
                .andExpect(jsonPath("$.items[0].book.id").value(book1.getId()))
                .andExpect(jsonPath("$.items[0].book.title").value("逾期书1"))
                .andExpect(jsonPath("$.items[0].book.isbn").value(book1.getIsbn()))
                .andExpect(jsonPath("$.items[0].copyBarcode").value(copy1.getBarcode()))
                .andExpect(jsonPath("$.items[0].borrowDate").value(today().minusDays(40).toString()))
                .andExpect(jsonPath("$.items[0].dueDate").value(today().minusDays(10).toString()))
                .andExpect(jsonPath("$.items[0].overdueDays").value(10))
                .andExpect(jsonPath("$.items[1].loanId").value(overdue3.getId()))
                .andExpect(jsonPath("$.items[1].reader.phone").value("13800000000"))
                .andExpect(jsonPath("$.items[1].overdueDays").value(3));
    }

    @Test
    @DisplayName("AC-STAT-04：排序白名单 overdueDays/dueDate 两向生效；白名单外或格式非法返回 400")
    void acStat04_sortWhitelist() throws Exception {
        asAdmin(get("/api/reports/overdue").param("sort", "dueDate,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].loanId").value(overdue10.getId()))
                .andExpect(jsonPath("$.items[1].loanId").value(overdue3.getId()));
        asAdmin(get("/api/reports/overdue").param("sort", "dueDate,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].loanId").value(overdue3.getId()));
        asAdmin(get("/api/reports/overdue").param("sort", "overdueDays,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].loanId").value(overdue3.getId()))
                .andExpect(jsonPath("$.items[1].loanId").value(overdue10.getId()));
        asAdmin(get("/api/reports/overdue").param("sort", "title,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(get("/api/reports/overdue").param("sort", "overdueDays"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("AC-STAT-04：分页参数生效（size=2 两页），越界 page/size 返回 400")
    void acStat04_pagination() throws Exception {
        // 第 3 条逾期：due today-5 → 5 天，排序居中
        Loan overdue5 = activeLoan(newCopy(newBook("逾期书5")), readerB, today().minusDays(35),
                today().minusDays(5));
        asAdmin(get("/api/reports/overdue").param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].overdueDays").value(10))
                .andExpect(jsonPath("$.items[1].overdueDays").value(5))
                .andExpect(jsonPath("$.items[1].loanId").value(overdue5.getId()));
        asAdmin(get("/api/reports/overdue").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].overdueDays").value(3));
        asAdmin(get("/api/reports/overdue").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(get("/api/reports/overdue").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(get("/api/reports/overdue").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("AC-STAT-04：应还日当天不出现；次日出现且 overdueDays=1（逾期口径与 T05/T06 一致）")
    void acStat04_dueDateBoundary() throws Exception {
        asAdmin(get("/api/reports/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
        mutableClock.setToday(today().plusDays(1));
        asAdmin(get("/api/reports/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[2].loanId").value(dueToday.getId()))
                .andExpect(jsonPath("$.items[2].overdueDays").value(1));
    }

    @Test
    @DisplayName("AC-STAT-04：时区边界——UTC 16:00（上海次日 00:00）即判逾期")
    void acStat04_timezoneBoundary() throws Exception {
        // dueToday 应还 2026-11-17；UTC 2026-11-17T16:00 = 上海 2026-11-18T00:00
        mutableClock.setInstant(Instant.parse("2026-11-17T16:00:00Z"));
        asAdmin(get("/api/reports/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[2].loanId").value(dueToday.getId()))
                .andExpect(jsonPath("$.items[2].overdueDays").value(1));
    }

    @Test
    @DisplayName("AC-STAT-04：无逾期记录时返回空 items、totalItems=0")
    void acStat04_emptyReport() throws Exception {
        // 清掉本测试类夹具产生的逾期：换一个干净时点（远早于所有 due_date）
        mutableClock.setToday(LocalDate.of(2026, 1, 1));
        asAdmin(get("/api/reports/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}