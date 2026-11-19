package com.example.library.loan;

import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 续借接口（design-v0.1 第 9.4 节；AC-LOAN-05、AC-RDR-02 借阅侧）。
 * D2 默认 false：读者调用续借 → 403（URL 授权）；管理员按第 9.4 节顺序校验。
 */
class RenewApiTest extends LoanTestBase {

    @Test
    @DisplayName("AC-LOAN-05：管理员续借成功——dueDate 从原应还日 +30 天、renewCount+1、记录经办人")
    void adminRenewSuccess() throws Exception {
        BookCopy copy = newCopy(newBook("续借成功"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        MvcResult result = asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(loan.getId()))
                .andExpect(jsonPath("$.dueDate").value(today().plusDays(50).toString()))
                .andExpect(jsonPath("$.renewCount").value(1))
                .andExpect(jsonPath("$.maxRenewals").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.renewable").value(false))
                .andReturn();
        assertThat(body(result)).doesNotContain("password");
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM loan WHERE id = ?", String.class, loan.getId()))
                .isEqualTo(today().plusDays(50).toString());
        assertThat(jdbcTemplate.queryForObject("SELECT last_renew_operator_id FROM loan WHERE id = ?", Long.class,
                loan.getId())).isEqualTo(adminUser.getId());
        // 副本状态不受续借影响
        assertThat(copyStatus(copy.getId())).isEqualTo("BORROWED");
    }

    @Test
    @DisplayName("AC-LOAN-05：应还日当天续借成功（当天不算逾期）")
    void renewOnDueDateSucceeds() throws Exception {
        BookCopy copy = newCopy(newBook("当天续借"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(30), today());
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value(today().plusDays(30).toString()));
    }

    @Test
    @DisplayName("AC-LOAN-05：再次续借 → 409 RENEW_LIMIT_REACHED，数据不变")
    void secondRenewRejected() throws Exception {
        BookCopy copy = newCopy(newBook("二次续借"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew")).andExpect(status().isOk());
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENEW_LIMIT_REACHED"));
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                loan.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM loan WHERE id = ?", String.class, loan.getId()))
                .isEqualTo(today().plusDays(50).toString());
    }

    @Test
    @DisplayName("AC-LOAN-05：借阅已逾期 → 409 LOAN_OVERDUE，数据不变")
    void overdueLoanRejected() throws Exception {
        BookCopy copy = newCopy(newBook("逾期续借"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(40), today().minusDays(1));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_OVERDUE"));
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                loan.getId())).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-05：已归还 → 409 LOAN_ALREADY_RETURNED")
    void returnedLoanRejected() throws Exception {
        BookCopy copy = newCopy(newBook("已还续借"));
        Loan loan = testDataFactory.returnedLoan(copy, readerA.reader(), today().minusDays(40),
                today().minusDays(10), today().minusDays(5), adminUser.getId());
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_RETURNED"));
    }

    @Test
    @DisplayName("AC-LOAN-05：读者有其他逾期借阅 → 409 READER_HAS_OVERDUE，数据不变")
    void otherOverdueRejected() throws Exception {
        BookCopy overdueCopy = newCopy(newBook("其他逾期"));
        activeLoan(overdueCopy, readerA, today().minusDays(40), today().minusDays(1));
        BookCopy copy = newCopy(newBook("本体未逾期"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_HAS_OVERDUE"));
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                loan.getId())).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-05/AC-RDR-02：读者停用 → 409 READER_DISABLED，数据不变")
    void disabledReaderRejected() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-renew-disabled", READER_PASSWORD, "停用续借",
                ReaderStatus.DISABLED);
        BookCopy copy = newCopy(newBook("停用续借"));
        Loan loan = activeLoan(copy, disabled, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_DISABLED"));
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                loan.getId())).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-05：借阅不存在 → 404 LOAN_NOT_FOUND")
    void unknownLoanRejected() throws Exception {
        asAdmin(post("/api/loans/99999/renew"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-LOAN-05/D2：默认读者调用续借 → 403 FORBIDDEN，数据不变")
    void readerForbiddenByDefault() throws Exception {
        BookCopy copy = newCopy(newBook("读者续借被拒"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asReaderA(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class,
                loan.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM loan WHERE id = ?", String.class, loan.getId()))
                .isEqualTo(today().plusDays(20).toString());
    }

    @Test
    @DisplayName("AC-LOAN-05：优先级——已归还 + 读者停用 → LOAN_ALREADY_RETURNED（第 2 步先于第 3 步）")
    void priorityReturnedBeforeDisabled() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-renew-prio1", READER_PASSWORD, "优先级续借1",
                ReaderStatus.DISABLED);
        BookCopy copy = newCopy(newBook("优先级续借1"));
        Loan loan = testDataFactory.returnedLoan(copy, disabled.reader(), today().minusDays(40),
                today().minusDays(10), today().minusDays(5), adminUser.getId());
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_RETURNED"));
    }

    @Test
    @DisplayName("AC-LOAN-05：优先级——读者停用 + 本条逾期 → READER_DISABLED（第 3 步先于第 4 步）")
    void priorityDisabledBeforeOverdue() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-renew-prio2", READER_PASSWORD, "优先级续借2",
                ReaderStatus.DISABLED);
        BookCopy copy = newCopy(newBook("优先级续借2"));
        Loan loan = activeLoan(copy, disabled, today().minusDays(40), today().minusDays(1));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_DISABLED"));
    }

    @Test
    @DisplayName("AC-LOAN-05：优先级——本条逾期 + 其他逾期 → LOAN_OVERDUE（第 4 步先于第 5 步）")
    void prioritySelfOverdueBeforeOtherOverdue() throws Exception {
        BookCopy otherCopy = newCopy(newBook("优先级其他逾期"));
        activeLoan(otherCopy, readerA, today().minusDays(50), today().minusDays(2));
        BookCopy copy = newCopy(newBook("优先级本体逾期"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(40), today().minusDays(1));
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_OVERDUE"));
    }
}