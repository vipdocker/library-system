package com.example.library.stats;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-STAT-01：GET /api/stats/overview 在固定测试数据下各计数项与预期完全一致。
 * 固定 today=2026-11-17（MutableClock 默认，Asia/Shanghai）。
 * 口径：activeLoans=未还且 due_date>=today；overdueLoans=未还且 due_date<today（应还日当天不算逾期）。
 */
class StatsOverviewApiTest extends StatsTestBase {

    @Test
    @DisplayName("AC-STAT-01：空库时 overview 全部计数为 0")
    void acStat01_emptyDatabaseAllZero() throws Exception {
        asAdmin(get("/api/stats/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBooks").value(0))
                .andExpect(jsonPath("$.totalCopies").value(0))
                .andExpect(jsonPath("$.availableCopies").value(0))
                .andExpect(jsonPath("$.borrowedCopies").value(0))
                .andExpect(jsonPath("$.unavailableCopies").value(0))
                .andExpect(jsonPath("$.totalReaders").value(1)) // 夹具 readerA
                .andExpect(jsonPath("$.activeReaders").value(1))
                .andExpect(jsonPath("$.activeLoans").value(0))
                .andExpect(jsonPath("$.overdueLoans").value(0))
                .andExpect(jsonPath("$.borrowsToday").value(0))
                .andExpect(jsonPath("$.returnsToday").value(0));
    }

    /**
     * 固定数据集（today=2026-11-17）：
     * 图书 3 本；副本 5 个：a1(BORROWED,L1)、a2(AVAILABLE,L4/L5 历史)、b1(BORROWED,L2 逾期)、
     * c1(UNAVAILABLE)、c2(BORROWED,L3 应还日=today)。
     * 读者 3 个：readerA/readerB ACTIVE、readerC DISABLED。
     * 借阅 5 条：L1 借 today 未还；L2 借 today-40 应还 today-10（逾期）；L3 借 today-30 应还 today（未逾期边界）；
     * L4 借 today-5 还 today；L5 借 today-20 还 today-2。
     */
    private void seedFixedDataset() {
        ReaderFixture readerB = testDataFactory.reader("stats-reader-b", READER_PASSWORD, "统计读者乙",
                ReaderStatus.ACTIVE);
        testDataFactory.reader("stats-reader-c", READER_PASSWORD, "统计读者丙", ReaderStatus.DISABLED);
        Book bookA = newBook("概览书A");
        Book bookB = newBook("概览书B");
        Book bookC = newBook("概览书C");
        BookCopy a1 = newCopy(bookA);
        BookCopy a2 = newCopy(bookA);
        BookCopy b1 = newCopy(bookB);
        unavailableCopy(bookC);
        BookCopy c2 = newCopy(bookC);
        activeLoan(a1, readerA, today(), today().plusDays(30));
        activeLoan(b1, readerB, today().minusDays(40), today().minusDays(10));
        activeLoan(c2, readerA, today().minusDays(30), today());
        returnedLoan(a2, readerB, today().minusDays(5), today().plusDays(25), today());
        returnedLoan(a2, readerA, today().minusDays(20), today().plusDays(10), today().minusDays(2));
    }

    @Test
    @DisplayName("AC-STAT-01：固定数据集下 overview 各计数项精确一致（含应还日当天不算逾期）")
    void acStat01_fixedDatasetExactCounts() throws Exception {
        seedFixedDataset();
        asAdmin(get("/api/stats/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBooks").value(3))
                .andExpect(jsonPath("$.totalCopies").value(5))
                .andExpect(jsonPath("$.availableCopies").value(1))
                .andExpect(jsonPath("$.borrowedCopies").value(3))
                .andExpect(jsonPath("$.unavailableCopies").value(1))
                .andExpect(jsonPath("$.totalReaders").value(3))
                .andExpect(jsonPath("$.activeReaders").value(2))
                .andExpect(jsonPath("$.activeLoans").value(2))   // L1 + L3（应还日=today 仍 ACTIVE）
                .andExpect(jsonPath("$.overdueLoans").value(1))  // L2
                .andExpect(jsonPath("$.borrowsToday").value(1))  // L1
                .andExpect(jsonPath("$.returnsToday").value(1)); // L4
    }

    @Test
    @DisplayName("AC-STAT-01：时钟拨到次日，应还日=原 today 的借阅转为逾期，today 计数归零")
    void acStat01_nextDayBoundary() throws Exception {
        seedFixedDataset();
        mutableClock.setToday(today().plusDays(1));
        asAdmin(get("/api/stats/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeLoans").value(1))   // 仅 L1
                .andExpect(jsonPath("$.overdueLoans").value(2))  // L2 + L3
                .andExpect(jsonPath("$.borrowsToday").value(0))
                .andExpect(jsonPath("$.returnsToday").value(0));
    }
}