package com.example.library.stats;

import java.time.LocalDate;
import java.util.stream.Stream;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-STAT-02：GET /api/stats/loans 按 DAY/MONTH 返回区间内每个周期的借出/归还数，
 * 无数据周期补 0；from>to 或跨度 >366 天返回 400 VALIDATION_FAILED。
 * 口径：borrowCount 按 borrow_date、returnCount 按 return_date（含已归还的借阅；续借不计入）。
 */
class LoanSeriesApiTest extends StatsTestBase {

    private BookCopy freshCopy() {
        Book book = newBook("序列书" + System.nanoTime());
        return newCopy(book);
    }

    @Test
    @DisplayName("AC-STAT-02：DAY 粒度跨月区间，首尾日期都包含，无数据日补 0，区间外不计入")
    void acStat02_dayGranularityCrossMonth() throws Exception {
        // 区间 2026-10-30 .. 2026-11-03（跨月，5 天）
        returnedLoan(freshCopy(), readerA, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 29),
                LocalDate.of(2026, 11, 1));
        returnedLoan(freshCopy(), readerA, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 11, 2));
        activeLoan(freshCopy(), readerA, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1));
        activeLoan(freshCopy(), readerA, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 12, 3));
        // 区间外：借 10-29、还 11-04，两端都不应计入
        returnedLoan(freshCopy(), readerA, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 28),
                LocalDate.of(2026, 11, 4));
        asAdmin(get("/api/stats/loans").param("from", "2026-10-30").param("to", "2026-11-03")
                        .param("granularity", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].period").value("2026-10-30"))
                .andExpect(jsonPath("$[0].borrowCount").value(1))
                .andExpect(jsonPath("$[0].returnCount").value(0))
                .andExpect(jsonPath("$[1].period").value("2026-10-31"))
                .andExpect(jsonPath("$[1].borrowCount").value(1))
                .andExpect(jsonPath("$[1].returnCount").value(0))
                .andExpect(jsonPath("$[2].period").value("2026-11-01"))
                .andExpect(jsonPath("$[2].borrowCount").value(1))
                .andExpect(jsonPath("$[2].returnCount").value(1))
                .andExpect(jsonPath("$[3].period").value("2026-11-02"))
                .andExpect(jsonPath("$[3].borrowCount").value(0))
                .andExpect(jsonPath("$[3].returnCount").value(1))
                .andExpect(jsonPath("$[4].period").value("2026-11-03"))
                .andExpect(jsonPath("$[4].borrowCount").value(1))
                .andExpect(jsonPath("$[4].returnCount").value(0));
    }

    @Test
    @DisplayName("AC-STAT-02：MONTH 粒度跨多月，边缘月只统计区间内日期，空月补 0")
    void acStat02_monthGranularityPartialMonths() throws Exception {
        // 区间 2026-09-15 .. 2026-12-05，覆盖 09/10/11/12 四个月
        returnedLoan(freshCopy(), readerA, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 9, 20)); // 借在区间前（不计），还在 9 月（计）
        returnedLoan(freshCopy(), readerA, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 2)); // 借 9-15（含首日），还 10 月
        returnedLoan(freshCopy(), readerA, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 20),
                LocalDate.of(2026, 11, 25));
        activeLoan(freshCopy(), readerA, LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 4)); // 含尾日
        activeLoan(freshCopy(), readerA, LocalDate.of(2026, 12, 6), LocalDate.of(2027, 1, 5)); // 区间后
        asAdmin(get("/api/stats/loans").param("from", "2026-09-15").param("to", "2026-12-05")
                        .param("granularity", "MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].period").value("2026-09"))
                .andExpect(jsonPath("$[0].borrowCount").value(1))
                .andExpect(jsonPath("$[0].returnCount").value(1))
                .andExpect(jsonPath("$[1].period").value("2026-10"))
                .andExpect(jsonPath("$[1].borrowCount").value(0))
                .andExpect(jsonPath("$[1].returnCount").value(1))
                .andExpect(jsonPath("$[2].period").value("2026-11"))
                .andExpect(jsonPath("$[2].borrowCount").value(1))
                .andExpect(jsonPath("$[2].returnCount").value(1))
                .andExpect(jsonPath("$[3].period").value("2026-12"))
                .andExpect(jsonPath("$[3].borrowCount").value(1))
                .andExpect(jsonPath("$[3].returnCount").value(0));
    }

    @Test
    @DisplayName("AC-STAT-02：无数据区间 DAY/MONTH 全部周期补 0")
    void acStat02_emptyPeriodsFilledWithZero() throws Exception {
        activeLoan(freshCopy(), readerA, today(), today().plusDays(30)); // 数据在 2026-11
        asAdmin(get("/api/stats/loans").param("from", "2026-01-01").param("to", "2026-01-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].period").value("2026-01-01"))
                .andExpect(jsonPath("$[0].borrowCount").value(0))
                .andExpect(jsonPath("$[0].returnCount").value(0))
                .andExpect(jsonPath("$[2].period").value("2026-01-03"))
                .andExpect(jsonPath("$[2].borrowCount").value(0))
                .andExpect(jsonPath("$[2].returnCount").value(0));
        asAdmin(get("/api/stats/loans").param("from", "2026-05-01").param("to", "2026-07-31")
                        .param("granularity", "MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].period").value("2026-05"))
                .andExpect(jsonPath("$[0].borrowCount").value(0))
                .andExpect(jsonPath("$[1].period").value("2026-06"))
                .andExpect(jsonPath("$[1].returnCount").value(0))
                .andExpect(jsonPath("$[2].period").value("2026-07"))
                .andExpect(jsonPath("$[2].borrowCount").value(0));
    }

    @Test
    @DisplayName("AC-STAT-02：缺省参数=最近 30 天（含今天），首尾边界日精确计入")
    void acStat02_defaultRangeLast30Days() throws Exception {
        activeLoan(freshCopy(), readerA, today().minusDays(29), today().plusDays(1)); // 首日（含）
        returnedLoan(freshCopy(), readerA, today(), today().plusDays(30), today());   // 尾日借+还
        activeLoan(freshCopy(), readerA, today().minusDays(30), today());             // 区间前一天（不计）
        asAdmin(get("/api/stats/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(30))
                .andExpect(jsonPath("$[0].period").value(today().minusDays(29).toString()))
                .andExpect(jsonPath("$[0].borrowCount").value(1))
                .andExpect(jsonPath("$[29].period").value(today().toString()))
                .andExpect(jsonPath("$[29].borrowCount").value(1))
                .andExpect(jsonPath("$[29].returnCount").value(1));
    }

    @Test
    @DisplayName("AC-STAT-02：跨度恰为 366 天（含两端 367 个日期）合法，返回 367 个点")
    void acStat02_span366Allowed() throws Exception {
        // 2025-11-16 .. 2026-11-17 自然日差 366
        asAdmin(get("/api/stats/loans").param("from", "2025-11-16").param("to", "2026-11-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(367))
                .andExpect(jsonPath("$[0].period").value("2025-11-16"))
                .andExpect(jsonPath("$[366].period").value("2026-11-17"));
    }

    @Test
    @DisplayName("AC-STAT-02：from 晚于 to 返回 400 VALIDATION_FAILED")
    void acStat02_fromAfterTo() throws Exception {
        asAdmin(get("/api/stats/loans").param("from", "2026-11-10").param("to", "2026-11-09"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    @DisplayName("AC-STAT-02：跨度 367 天（>366）返回 400 VALIDATION_FAILED")
    void acStat02_span367Rejected() throws Exception {
        // 2025-11-15 .. 2026-11-17 自然日差 367
        asAdmin(get("/api/stats/loans").param("from", "2025-11-15").param("to", "2026-11-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest(name = "AC-STAT-02 非法日期 {0} → 400")
    @ValueSource(strings = {"2026-13-01", "2026-02-30", "2026-1-01", "20261101", "abc", "2026-11-17 "})
    @DisplayName("AC-STAT-02：日期格式非法（溢出日期、非补零、非日期文本）返回 400")
    void acStat02_invalidDateFormat(String badDate) throws Exception {
        // 尾随空格的用例按第 8.1 节 trim 后合法，单独处理
        if (badDate.endsWith(" ")) {
            asAdmin(get("/api/stats/loans").param("from", badDate).param("to", "2026-11-17"))
                    .andExpect(status().isOk());
            return;
        }
        asAdmin(get("/api/stats/loans").param("from", badDate).param("to", "2026-11-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    @DisplayName("AC-STAT-02：续借不计入借阅次数（真实续借接口调用后 borrowCount 不变）")
    void acStat02_renewNotCountedAsBorrow() throws Exception {
        var loan = activeLoan(freshCopy(), readerA, today(), today().plusDays(30));
        asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isOk());
        asAdmin(get("/api/stats/loans").param("from", today().toString()).param("to", today().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].borrowCount").value(1))
                .andExpect(jsonPath("$[0].returnCount").value(0));
    }

    @Test
    @DisplayName("AC-STAT-02：granularity 非 DAY/MONTH 返回 400 VALIDATION_FAILED")
    void acStat02_invalidGranularity() throws Exception {
        Stream.of("WEEK", "day", "YEAR").forEach(value -> {
            try {
                asAdmin(get("/api/stats/loans").param("granularity", value))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.fieldErrors[0].field").value("granularity"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}