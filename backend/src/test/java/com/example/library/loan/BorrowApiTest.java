package com.example.library.loan;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 借书接口（design-v0.1 第 9.2 节；AC-LOAN-01/02、AC-RDR-02 借阅侧）。
 * 校验顺序命中第一条即返回；每种拒绝都断言数据完全不变。
 */
class BorrowApiTest extends LoanTestBase {

    @Test
    @DisplayName("AC-LOAN-01：正常借书 201，borrowDate=今天、dueDate=+30 天、副本 BORROWED、可借数减 1、记录经办人")
    void borrowSuccess() throws Exception {
        Book book = newBook("借阅成功之书");
        BookCopy copy = newCopy(book);
        asAdmin(get("/api/books/" + book.getId()))
                .andExpect(jsonPath("$.availableCopies").value(1));

        MvcResult result = borrowOk(readerA.reader().getCardNo(), copy.getBarcode());
        jsonPath("$.id").exists().match(result);
        jsonPath("$.reader.id").value(readerA.reader().getId()).match(result);
        jsonPath("$.reader.cardNo").value(readerA.reader().getCardNo()).match(result);
        jsonPath("$.reader.name").value("张三").match(result);
        jsonPath("$.copy.id").value(copy.getId()).match(result);
        jsonPath("$.copy.barcode").value(copy.getBarcode()).match(result);
        jsonPath("$.book.id").value(book.getId()).match(result);
        jsonPath("$.book.title").value("借阅成功之书").match(result);
        jsonPath("$.book.author").value("作者借阅成功之书").match(result);
        jsonPath("$.book.isbn").value(book.getIsbn()).match(result);
        jsonPath("$.borrowDate").value(today().toString()).match(result);
        jsonPath("$.dueDate").value(today().plusDays(30).toString()).match(result);
        jsonPath("$.returnDate").value(org.hamcrest.Matchers.nullValue()).match(result);
        jsonPath("$.renewCount").value(0).match(result);
        jsonPath("$.maxRenewals").value(1).match(result);
        jsonPath("$.status").value("ACTIVE").match(result);
        jsonPath("$.overdueDays").value(0).match(result);
        jsonPath("$.renewable").value(true).match(result);

        assertThat(copyStatus(copy.getId())).isEqualTo("BORROWED");
        long loanId = idOf(result);
        assertThat(jdbcTemplate.queryForObject("SELECT borrow_operator_id FROM loan WHERE id = ?", Long.class, loanId))
                .isEqualTo(adminUser.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT renew_count FROM loan WHERE id = ?", Integer.class, loanId))
                .isZero();
        asAdmin(get("/api/books/" + book.getId()))
                .andExpect(jsonPath("$.availableCopies").value(0))
                .andExpect(jsonPath("$.totalCopies").value(1));
    }

    @Test
    @DisplayName("AC-LOAN-02：读者不存在 → 404 READER_NOT_FOUND，数据不变")
    void readerNotFound() throws Exception {
        BookCopy copy = newCopy(newBook("读者不存在"));
        asAdmin(borrowRequest("R999999", copy.getBarcode()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
        assertThat(loanCount()).isZero();
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-02：副本不存在 → 404 COPY_NOT_FOUND，数据不变")
    void copyNotFound() throws Exception {
        asAdmin(borrowRequest(readerA.reader().getCardNo(), "C99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
        assertThat(loanCount()).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-02/AC-RDR-02：读者停用 → 409 READER_DISABLED，数据不变")
    void disabledReader() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-disabled", READER_PASSWORD, "停用读者",
                ReaderStatus.DISABLED);
        BookCopy copy = newCopy(newBook("停用读者"));
        asAdmin(borrowRequest(disabled.reader().getCardNo(), copy.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_DISABLED"));
        assertThat(loanCount()).isZero();
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-RDR-02：停用读者重新启用后可以借书")
    void reenabledReaderCanBorrow() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-reenable", READER_PASSWORD, "重启用读者",
                ReaderStatus.DISABLED);
        BookCopy copy = newCopy(newBook("重新启用"));
        asAdmin(borrowRequest(disabled.reader().getCardNo(), copy.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_DISABLED"));
        asAdmin(put("/api/readers/" + disabled.reader().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重启用读者\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        borrowOk(disabled.reader().getCardNo(), copy.getBarcode());
    }

    @Test
    @DisplayName("AC-LOAN-02：读者有逾期未还 → 409 READER_HAS_OVERDUE，数据不变")
    void readerHasOverdue() throws Exception {
        Book book = newBook("逾期读者");
        BookCopy overdueCopy = newCopy(book);
        activeLoan(overdueCopy, readerA, today().minusDays(40), today().minusDays(10));
        BookCopy freshCopy = newCopy(newBook("逾期读者新书"));
        asAdmin(borrowRequest(readerA.reader().getCardNo(), freshCopy.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_HAS_OVERDUE"));
        assertThat(loanCount()).isEqualTo(1);
        assertThat(copyStatus(freshCopy.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-02：在借 5 本时第 5 本成功、第 6 本 → 409 LOAN_LIMIT_EXCEEDED（details.limit/current）")
    void loanLimitExceeded() throws Exception {
        BookCopy[] copies = new BookCopy[6];
        for (int i = 0; i < 6; i++) {
            copies[i] = newCopy(newBook("上限书" + i));
        }
        for (int i = 0; i < 5; i++) {
            borrowOk(readerA.reader().getCardNo(), copies[i].getBarcode());
        }
        asAdmin(borrowRequest(readerA.reader().getCardNo(), copies[5].getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.limit").value(5))
                .andExpect(jsonPath("$.details.current").value(5));
        assertThat(loanCount()).isEqualTo(5);
        assertThat(copyStatus(copies[5].getId())).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-02：副本已借出 → 409 COPY_NOT_AVAILABLE，数据不变")
    void copyAlreadyBorrowed() throws Exception {
        BookCopy copy = newCopy(newBook("已借出"));
        borrowOk(readerA.reader().getCardNo(), copy.getBarcode());
        asAdmin(borrowRequest(readerB.reader().getCardNo(), copy.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_NOT_AVAILABLE"));
        assertThat(loanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-LOAN-02：副本 UNAVAILABLE → 409 COPY_NOT_AVAILABLE，数据不变")
    void copyUnavailable() throws Exception {
        BookCopy copy = testDataFactory.copy(newBook("不可用"), CopyStatus.UNAVAILABLE);
        asAdmin(borrowRequest(readerA.reader().getCardNo(), copy.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_NOT_AVAILABLE"));
        assertThat(loanCount()).isZero();
        assertThat(copyStatus(copy.getId())).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("AC-LOAN-02/D4②：同一读者借同书目另一副本 → 409 DUPLICATE_BOOK_LOAN，数据不变")
    void duplicateBookLoan() throws Exception {
        Book book = newBook("同书目双副本");
        BookCopy copy1 = newCopy(book);
        BookCopy copy2 = newCopy(book);
        borrowOk(readerA.reader().getCardNo(), copy1.getBarcode());
        asAdmin(borrowRequest(readerA.reader().getCardNo(), copy2.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BOOK_LOAN"));
        assertThat(loanCount()).isEqualTo(1);
        assertThat(copyStatus(copy2.getId())).isEqualTo("AVAILABLE");
        // 其他读者不受该规则影响
        borrowOk(readerB.reader().getCardNo(), copy2.getBarcode());
    }

    @Test
    @DisplayName("AC-LOAN-02：优先级——停用读者 + 不存在副本 → READER_DISABLED（第 2 步先于第 5 步）")
    void priorityDisabledBeforeCopyNotFound() throws Exception {
        ReaderFixture disabled = testDataFactory.reader("loan-prio-1", READER_PASSWORD, "优先级1",
                ReaderStatus.DISABLED);
        asAdmin(borrowRequest(disabled.reader().getCardNo(), "C99999999"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_DISABLED"));
    }

    @Test
    @DisplayName("AC-LOAN-02：优先级——有逾期 + 已达上限 → READER_HAS_OVERDUE（第 3 步先于第 4 步）")
    void priorityOverdueBeforeLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            BookCopy copy = newCopy(newBook("优先级逾期" + i));
            activeLoan(copy, readerA, today().minusDays(40),
                    i == 0 ? today().minusDays(1) : today().plusDays(20));
        }
        BookCopy fresh = newCopy(newBook("优先级逾期新书"));
        asAdmin(borrowRequest(readerA.reader().getCardNo(), fresh.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READER_HAS_OVERDUE"));
    }

    @Test
    @DisplayName("AC-LOAN-02：优先级——已达上限 + 不存在副本 → LOAN_LIMIT_EXCEEDED（第 4 步先于第 5 步）")
    void priorityLimitBeforeCopyNotFound() throws Exception {
        for (int i = 0; i < 5; i++) {
            activeLoan(newCopy(newBook("优先级上限" + i)), readerA, today().minusDays(10), today().plusDays(20));
        }
        asAdmin(borrowRequest(readerA.reader().getCardNo(), "C99999999"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("AC-LOAN-02：优先级——副本不可用 + 同书目重复 → COPY_NOT_AVAILABLE（第 6 步先于第 7 步）")
    void priorityCopyStatusBeforeDuplicate() throws Exception {
        Book book = newBook("优先级重复");
        BookCopy copy1 = newCopy(book);
        BookCopy copy2 = testDataFactory.copy(book, CopyStatus.UNAVAILABLE);
        borrowOk(readerA.reader().getCardNo(), copy1.getBarcode());
        asAdmin(borrowRequest(readerA.reader().getCardNo(), copy2.getBarcode()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("AC-LOAN-02：缺失/空白字段 → 400 VALIDATION_FAILED 附 fieldErrors，数据不变")
    void validationErrors() throws Exception {
        asAdmin(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='readerCardNo')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='copyBarcode')]").exists());
        asAdmin(borrowRequest("   ", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(loanCount()).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-01：卡号/条码首尾空白被去除后正常借书（第 8.1 节）")
    void trimsInput() throws Exception {
        BookCopy copy = newCopy(newBook("空白输入"));
        borrowOk("  " + readerA.reader().getCardNo() + "  ", "  " + copy.getBarcode() + "  ");
        assertThat(copyStatus(copy.getId())).isEqualTo("BORROWED");
    }
}