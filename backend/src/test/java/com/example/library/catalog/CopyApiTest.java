package com.example.library.catalog;

import java.time.LocalDate;
import java.util.List;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.repository.BookCopyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-COPY-01（追加 1..50 个副本、唯一条码 C+8 位数字、越界 400）、
 * AC-COPY-02（AVAILABLE/UNAVAILABLE 切换、在借 409 COPY_ON_LOAN、BORROWED 400、
 * 删除与借阅历史检查、UNAVAILABLE 不可借出的数据层前提）。
 * 借书接口本身属于 T06；“UNAVAILABLE 不能借出”在这里用生产借书使用的同一原子条件
 * UPDATE（markBorrowedIfAvailable）验证数据层拒绝，完整借书拒绝路径由 T06 的 AC-LOAN-02 覆盖。
 */
class CopyApiTest extends CatalogTestBase {

    @Autowired
    private BookCopyRepository bookCopyRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Book book;

    private Book fixtureBook() {
        if (book == null) {
            book = testDataFactory.book("9787115428028", "样本书", "作者", null);
        }
        return book;
    }

    // ---- AC-COPY-01 ----

    @Test
    @DisplayName("AC-COPY-01：管理员列出图书副本 200，条码格式 C+8 位数字，bookId 正确")
    void listCopies() throws Exception {
        Book b = fixtureBook();
        BookCopy c1 = testDataFactory.copy(b, CopyStatus.AVAILABLE);
        BookCopy c2 = testDataFactory.copy(b, CopyStatus.UNAVAILABLE);
        asAdmin(get("/api/books/" + b.getId() + "/copies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(c1.getId()))
                .andExpect(jsonPath("$[0].bookId").value(b.getId()))
                .andExpect(jsonPath("$[0].barcode").value(c1.getBarcode()))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-11-17T02:00:00Z"))
                .andExpect(jsonPath("$[1].status").value("UNAVAILABLE"));
    }

    @Test
    @DisplayName("AC-COPY-01：追加 3 个副本 201，返回新建副本；条码唯一且格式正确；总数增加")
    void addCopies() throws Exception {
        Book b = fixtureBook();
        testDataFactory.copy(b, CopyStatus.AVAILABLE);
        MvcResult result = asAdmin(post("/api/books/" + b.getId() + "/copies")
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();
        List<String> barcodes = com.jayway.jsonpath.JsonPath.read(body(result), "$[*].barcode");
        assertThat(barcodes).doesNotHaveDuplicates();
        assertThat(barcodes).allSatisfy(barcode -> assertThat(barcode).matches("C\\d{8}"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_copy WHERE book_id = ?",
                Long.class, b.getId())).isEqualTo(4);
        // 新副本全部 AVAILABLE
        assertThat((List<String>) com.jayway.jsonpath.JsonPath.read(body(result), "$[*].status"))
                .containsOnly("AVAILABLE");
    }

    @Test
    @DisplayName("AC-COPY-01：count 边界 1 和 50 合法")
    void addCopiesBounds() throws Exception {
        Book b = fixtureBook();
        asAdmin(post("/api/books/" + b.getId() + "/copies")
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1));
        asAdmin(post("/api/books/" + b.getId() + "/copies")
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(50));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_copy WHERE book_id = ?",
                Long.class, b.getId())).isEqualTo(51);
    }

    @Test
    @DisplayName("AC-COPY-01：count 越界（0/51）或缺失返回 400 VALIDATION_FAILED")
    void addCopiesInvalidCount() throws Exception {
        Book b = fixtureBook();
        for (String invalid : new String[]{"{\"count\":0}", "{\"count\":51}", "{}"}) {
            asAdmin(post("/api/books/" + b.getId() + "/copies")
                    .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_copy", Long.class)).isZero();
    }

    @Test
    @DisplayName("AC-COPY-01：对不存在的图书列出/追加副本返回 404 BOOK_NOT_FOUND")
    void copiesBookNotFound() throws Exception {
        asAdmin(get("/api/books/999999/copies"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
        asAdmin(post("/api/books/999999/copies")
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    // ---- AC-COPY-02 ----

    @Test
    @DisplayName("AC-COPY-02：状态在 AVAILABLE 与 UNAVAILABLE 之间切换 200，updatedAt 更新")
    void toggleStatus() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        mutableClock.advance(java.time.Duration.ofMinutes(1));
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(copy.getId()))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.barcode").value(copy.getBarcode()));
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        assertThat(bookCopyRepository.findById(copy.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(mutableClock.instant());
    }

    @Test
    @DisplayName("AC-COPY-02：请求设置 BORROWED 返回 400；非法状态值返回 400 且 fieldErrors 指向 status")
    void patchInvalidStatusValues() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BORROWED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'status')]").exists());
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FOO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'status')]").exists());
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(bookCopyRepository.findById(copy.getId()).orElseThrow().getStatus())
                .isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    @DisplayName("AC-COPY-02：在借副本切换状态返回 409 COPY_ON_LOAN，状态不变")
    void patchOnLoanCopy() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        LocalDate today = LocalDate.now(mutableClock);
        testDataFactory.activeLoan(copy, readerFixture.reader(), today, today.plusDays(30), adminUser.getId());
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_ON_LOAN"));
        assertThat(bookCopyRepository.findById(copy.getId()).orElseThrow().getStatus())
                .isEqualTo(CopyStatus.BORROWED);
    }

    @Test
    @DisplayName("AC-COPY-02：修改不存在的副本返回 404 COPY_NOT_FOUND")
    void patchNotFound() throws Exception {
        asAdmin(patch("/api/copies/999999")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-COPY-02：删除从未借出的副本返回 204")
    void deleteNeverBorrowedCopy() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        asAdmin(delete("/api/copies/" + copy.getId())).andExpect(status().isNoContent());
        assertThat(bookCopyRepository.findById(copy.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC-COPY-02：删除有借阅历史（已归还）的副本返回 409 COPY_HAS_LOAN_HISTORY，数据不变")
    void deleteCopyWithHistory() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        LocalDate today = LocalDate.now(mutableClock);
        testDataFactory.returnedLoan(copy, readerFixture.reader(), today.minusDays(40), today.minusDays(10),
                today.minusDays(5), adminUser.getId());
        asAdmin(delete("/api/copies/" + copy.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_HAS_LOAN_HISTORY"));
        assertThat(bookCopyRepository.findById(copy.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-COPY-02：删除在借副本返回 409 COPY_ON_LOAN，数据不变")
    void deleteOnLoanCopy() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        LocalDate today = LocalDate.now(mutableClock);
        testDataFactory.activeLoan(copy, readerFixture.reader(), today, today.plusDays(30), adminUser.getId());
        asAdmin(delete("/api/copies/" + copy.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPY_ON_LOAN"));
        assertThat(bookCopyRepository.findById(copy.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-COPY-02：删除不存在的副本返回 404 COPY_NOT_FOUND")
    void deleteNotFound() throws Exception {
        asAdmin(delete("/api/copies/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-COPY-02：UNAVAILABLE 副本不能被借出（生产借书使用的原子占用 UPDATE 返回 0 行）")
    void unavailableCopyCannotBeBorrowed() throws Exception {
        BookCopy copy = testDataFactory.copy(fixtureBook(), CopyStatus.AVAILABLE);
        asAdmin(patch("/api/copies/" + copy.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk());
        transactionTemplate.executeWithoutResult(tx ->
                assertThat(bookCopyRepository.markBorrowedIfAvailable(copy.getId(), mutableClock.instant()))
                        .isZero());
        assertThat(bookCopyRepository.findById(copy.getId()).orElseThrow().getStatus())
                .isEqualTo(CopyStatus.UNAVAILABLE);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class)).isZero();
    }
}