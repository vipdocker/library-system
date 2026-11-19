package com.example.library.stats;

import java.time.LocalDate;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-STAT-03：GET /api/stats/top-books 按区间内借阅次数降序，次数相同按书名升序，limit 生效。
 * 口径：borrowCount 按 borrow_date 计数，含之后已归还的借阅；续借不产生新借阅行。
 * 固定区间 2026-11-01 .. 2026-11-17（today）。
 */
class TopBooksApiTest extends StatsTestBase {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 17);

    private Book alpha;
    private Book beta;
    private Book gamma;

    @BeforeEach
    void seedTopBooksData() {
        alpha = newBook("Alpha");
        beta = newBook("Beta");
        gamma = newBook("Gamma");
        Book delta = newBook("Delta");
        // Alpha：3 次（2 还 1 在借）
        returnedLoan(newCopy(alpha), readerA, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 11, 5));
        returnedLoan(newCopy(alpha), readerA, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5),
                LocalDate.of(2026, 11, 10));
        activeLoan(newCopy(alpha), readerA, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 10));
        // Beta：3 次（与 Alpha 打平，title 升序应排 Alpha 之后；含尾日 11-17）
        returnedLoan(newCopy(beta), readerA, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 2),
                LocalDate.of(2026, 11, 3));
        activeLoan(newCopy(beta), readerA, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 12, 3));
        activeLoan(newCopy(beta), readerA, TO, LocalDate.of(2026, 12, 17));
        // Gamma：1 次（首日 11-01）
        activeLoan(newCopy(gamma), readerA, FROM, LocalDate.of(2026, 12, 1));
        // Delta：区间外 1 次（10-31），不应出现
        returnedLoan(newCopy(delta), readerA, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 11, 2));
    }

    private org.springframework.test.web.servlet.ResultActions topBooks(String limit) throws Exception {
        var request = get("/api/stats/top-books").param("from", FROM.toString()).param("to", TO.toString());
        if (limit != null) {
            request.param("limit", limit);
        }
        return asAdmin(request);
    }

    @Test
    @DisplayName("AC-STAT-03：默认 limit=10，borrowCount 降序、同数按 title 升序，字段完整，区间外不计")
    void acStat03_orderAndFields() throws Exception {
        topBooks(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].bookId").value(alpha.getId()))
                .andExpect(jsonPath("$[0].title").value("Alpha"))
                .andExpect(jsonPath("$[0].author").value("作者·Alpha"))
                .andExpect(jsonPath("$[0].isbn").value(alpha.getIsbn()))
                .andExpect(jsonPath("$[0].borrowCount").value(3))
                .andExpect(jsonPath("$[1].title").value("Beta"))
                .andExpect(jsonPath("$[1].borrowCount").value(3))
                .andExpect(jsonPath("$[2].title").value("Gamma"))
                .andExpect(jsonPath("$[2].borrowCount").value(1));
    }

    @Test
    @DisplayName("AC-STAT-03：limit=2 只返回前 2 名；limit=1 只返回第 1 名")
    void acStat03_limitApplied() throws Exception {
        topBooks("2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Alpha"))
                .andExpect(jsonPath("$[1].title").value("Beta"));
        topBooks("1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Alpha"));
    }

    @Test
    @DisplayName("AC-STAT-03：limit 越界（0、51）或非数字返回 400 VALIDATION_FAILED")
    void acStat03_limitValidation() throws Exception {
        topBooks("0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
        topBooks("51")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        topBooks("abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("AC-STAT-03：区间非法（from>to、格式错误、跨度>366）返回 400 VALIDATION_FAILED")
    void acStat03_rangeValidation() throws Exception {
        asAdmin(get("/api/stats/top-books").param("from", "2026-11-10").param("to", "2026-11-09"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(get("/api/stats/top-books").param("from", "2026-02-30").param("to", "2026-11-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(get("/api/stats/top-books").param("from", "2025-11-15").param("to", "2026-11-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("AC-STAT-03：区间内无借阅时返回空数组")
    void acStat03_emptyRange() throws Exception {
        asAdmin(get("/api/stats/top-books").param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}