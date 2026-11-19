package com.example.library.loan;

import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D2=允许（lib.loan.reader-self-renew=true，测试配置打开开关）：
 * 读者可以续借本人借阅（AC-LOAN-05）；续借他人借阅 → 404 LOAN_NOT_FOUND 且数据不变（AC-AUTH-05，防 IDOR）。
 * URL 授权放行由 SecurityConfig 的 D2 开关保证（AuthorizationMatrixD2Test 已覆盖授权层）。
 */
@TestPropertySource(properties = "lib.loan.reader-self-renew=true")
class RenewSelfServiceTest extends LoanTestBase {

    @Test
    @DisplayName("AC-LOAN-05/D2=允许：读者续借本人借阅成功，dueDate 从原应还日 +30 天")
    void readerRenewsOwnLoan() throws Exception {
        BookCopy copy = newCopy(newBook("读者自助续借"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value(today().plusDays(50).toString()))
                .andExpect(jsonPath("$.renewCount").value(1))
                .andExpect(jsonPath("$.renewable").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT last_renew_operator_id FROM loan WHERE id = ?", Long.class,
                loan.getId())).isEqualTo(readerA.user().getId());
    }

    @Test
    @DisplayName("AC-AUTH-05/D2=允许：读者续借他人借阅 → 404 LOAN_NOT_FOUND，数据完全不变")
    void readerCannotRenewOthersLoan() throws Exception {
        BookCopy copy = newCopy(newBook("他人借阅"));
        Loan loan = activeLoan(copy, readerB, today().minusDays(10), today().plusDays(20));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                loan.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM loan WHERE id = ?", String.class, loan.getId()))
                .isEqualTo(today().plusDays(20).toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_renew_operator_id FROM loan WHERE id = ?", Long.class, loan.getId())).isNull();
    }

    @Test
    @DisplayName("AC-LOAN-05/D2=允许：读者第二次续借本人借阅 → 409 RENEW_LIMIT_REACHED")
    void readerSecondRenewRejected() throws Exception {
        BookCopy copy = newCopy(newBook("读者二次续借"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew")).andExpect(status().isOk());
        asReaderA(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENEW_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("AC-LOAN-05/D2=允许：读者续借本人已逾期借阅 → 409 LOAN_OVERDUE")
    void readerOverdueRejected() throws Exception {
        BookCopy copy = newCopy(newBook("读者逾期续借"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(40), today().minusDays(1));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_OVERDUE"));
    }

    @Test
    @DisplayName("AC-LOAN-05/D2=允许：读者有其他逾期时续借本人未逾期借阅 → 409 READER_HAS_OVERDUE")
    void readerOtherOverdueRejected() throws Exception {
        activeLoan(newCopy(newBook("读者其他逾期")), readerA, today().minusDays(40), today().minusDays(1));
        BookCopy copy = newCopy(newBook("读者本体未逾期"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_HAS_OVERDUE"));
    }

    @Test
    @DisplayName("AC-LOAN-05/D2=允许：管理员仍然可以续借任何借阅")
    void adminStillAllowedWhenSelfRenewEnabled() throws Exception {
        BookCopy copy = newCopy(newBook("管理员续借D2开"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewCount").value(1));
    }
}