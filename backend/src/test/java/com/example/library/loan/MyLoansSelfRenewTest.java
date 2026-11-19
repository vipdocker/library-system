package com.example.library.loan;

import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D2=允许时 /me 的 renewable（AC-LOAN-05/07、AC-AUTH-05）：
 * 本人可续借的借阅 renewable=true；续借后次数用尽 → false。
 */
@TestPropertySource(properties = "lib.loan.reader-self-renew=true")
class MyLoansSelfRenewTest extends LoanTestBase {

    @Test
    @DisplayName("AC-LOAN-07/D2=允许：/me 中本人可续借借阅 renewable=true，续借后 false")
    void renewableForOwnEligibleLoan() throws Exception {
        BookCopy copy = newCopy(newBook("自助续借列表"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asReaderA(get("/api/loans/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(loan.getId()))
                .andExpect(jsonPath("$.items[0].renewable").value(true));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew")).andExpect(status().isOk());
        asReaderA(get("/api/loans/me"))
                .andExpect(jsonPath("$.items[0].renewable").value(false));
    }

    @Test
    @DisplayName("AC-LOAN-07/D2=允许：/me 中逾期借阅 renewable=false 且标出逾期天数")
    void overdueNotRenewable() throws Exception {
        BookCopy copy = newCopy(newBook("自助逾期列表"));
        activeLoan(copy, readerA, today().minusDays(40), today().minusDays(3));
        asReaderA(get("/api/loans/me"))
                .andExpect(jsonPath("$.items[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.items[0].overdueDays").value(3))
                .andExpect(jsonPath("$.items[0].renewable").value(false));
    }
}