package com.example.library.loan;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 还书接口（design-v0.1 第 9.3 节；AC-LOAN-04）。
 * 按 id 与按条码两条路径；逾期照常归还；重复还书与无在借记录的错误码。
 */
class ReturnApiTest extends LoanTestBase {

    @Test
    @DisplayName("AC-LOAN-04：按 id 还书 200，status=RETURNED、returnDate=今天、副本变回 AVAILABLE、记录经办人")
    void returnByIdSuccess() throws Exception {
        Book book = newBook("按id还书");
        BookCopy copy = newCopy(book);
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));

        MvcResult result = asAdmin(post("/api/loans/" + loan.getId() + "/return"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(loan.getId()))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnDate").value(today().toString()))
                .andExpect(jsonPath("$.overdueDays").value(0))
                .andExpect(jsonPath("$.renewable").value(false))
                .andReturn();
        assertThat(body(result)).doesNotContain("password");
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT return_operator_id FROM loan WHERE id = ?", Long.class,
                loan.getId())).isEqualTo(adminUser.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT return_date FROM loan WHERE id = ?", String.class,
                loan.getId())).isEqualTo(today().toString());
        asAdmin(get("/api/books/" + book.getId()))
                .andExpect(jsonPath("$.availableCopies").value(1));
    }

    @Test
    @DisplayName("AC-LOAN-04：逾期归还照常完成，overdueDays>0")
    void overdueReturnSucceeds() throws Exception {
        BookCopy copy = newCopy(newBook("逾期归还"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(40), today().minusDays(5));
        asAdmin(post("/api/loans/" + loan.getId() + "/return"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.overdueDays").value(5));
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-04：重复还书 → 409 LOAN_ALREADY_RETURNED，数据不变")
    void doubleReturn() throws Exception {
        BookCopy copy = newCopy(newBook("重复还书"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/" + loan.getId() + "/return")).andExpect(status().isOk());
        String returnedAtBefore = jdbcTemplate.queryForObject(
                "SELECT returned_at FROM loan WHERE id = ?", String.class, loan.getId());
        asAdmin(post("/api/loans/" + loan.getId() + "/return"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_RETURNED"));
        assertThat(jdbcTemplate.queryForObject("SELECT returned_at FROM loan WHERE id = ?", String.class,
                loan.getId())).isEqualTo(returnedAtBefore);
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-04：借阅不存在 → 404 LOAN_NOT_FOUND")
    void returnUnknownLoan() throws Exception {
        asAdmin(post("/api/loans/99999/return"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-LOAN-04：按条码还书 200，副本变回 AVAILABLE")
    void returnByBarcodeSuccess() throws Exception {
        BookCopy copy = newCopy(newBook("按条码还书"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"copyBarcode\":\"" + copy.getBarcode() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(loan.getId()))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnDate").value(today().toString()));
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-04：按条码还书——条码不存在 → 404 COPY_NOT_FOUND")
    void returnByBarcodeUnknownCopy() throws Exception {
        asAdmin(post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"copyBarcode\":\"C99999999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-LOAN-04：按条码还书——副本从未借出 → 404 ACTIVE_LOAN_NOT_FOUND")
    void returnByBarcodeNeverBorrowed() throws Exception {
        BookCopy copy = newCopy(newBook("从未借出"));
        asAdmin(post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"copyBarcode\":\"" + copy.getBarcode() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_LOAN_NOT_FOUND"));
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-04：按条码还书——已归还后再次提交 → 404 ACTIVE_LOAN_NOT_FOUND")
    void returnByBarcodeAfterReturn() throws Exception {
        BookCopy copy = newCopy(newBook("还后再还"));
        activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"copyBarcode\":\"" + copy.getBarcode() + "\"}"))
                .andExpect(status().isOk());
        asAdmin(post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"copyBarcode\":\"" + copy.getBarcode() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVE_LOAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-LOAN-04/第9.1节：停用读者的在借借阅可以由管理员归还")
    void disabledReaderCanBeReturnedByAdmin() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-ret-disabled", READER_PASSWORD, "停用归还",
                ReaderStatus.DISABLED);
        BookCopy copy = newCopy(newBook("停用归还"));
        Loan loan = activeLoan(copy, disabled, today().minusDays(10), today().plusDays(20));
        asAdmin(post("/api/loans/" + loan.getId() + "/return"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-04：按条码还书缺失字段 → 400 VALIDATION_FAILED")
    void returnByBarcodeValidation() throws Exception {
        asAdmin(post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='copyBarcode')]").exists());
    }
}