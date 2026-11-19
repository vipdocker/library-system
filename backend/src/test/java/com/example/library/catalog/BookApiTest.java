package com.example.library.catalog;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Category;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.LoanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-BOOK-01（创建/规范化/严格 ISBN 校验/副本默认值/400/409）、
 * AC-BOOK-02（修改/删除与借阅历史检查）、AC-BOOK-03（totalCopies/availableCopies 派生计数）。
 * 借还动作本身属于 T06；本测试用共享 Repository 的原子条件 UPDATE（与生产借还同一数据操作）
 * 模拟借出/归还，验证计数立即反映变化。
 */
class BookApiTest extends CatalogTestBase {

    @Autowired
    private BookCopyRepository bookCopyRepository;
    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final String VALID_ISBN_13 = "9787115428028";
    private static final String OTHER_ISBN_13 = "9787115544834";

    private MvcResult createBook(String json) throws Exception {
        return asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private static String bookJson(String isbn, String title, String author) {
        return "{\"isbn\":\"" + isbn + "\",\"title\":\"" + title + "\",\"author\":\"" + author + "\"}";
    }

    // ---- AC-BOOK-01 ----

    @Test
    @DisplayName("AC-BOOK-01：合法字段新建图书 201；ISBN 带连字符按规范化结果保存；默认生成 1 个副本")
    void createBookNormalizesIsbnAndDefaultCopy() throws Exception {
        MvcResult result = createBook("{\"isbn\":\"978-7-115-42802-8\",\"title\":\"Python编程\","
                + "\"author\":\"Eric Matthes\",\"publisher\":\"人民邮电出版社\",\"publishYear\":2016,"
                + "\"description\":\"入门书\"}");
        long id = idOf(result);
        assertThat(body(result)).contains("\"isbn\":\"" + VALID_ISBN_13 + "\"");
        mockMvc.perform(get("/api/books/" + id).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value(VALID_ISBN_13))
                .andExpect(jsonPath("$.title").value("Python编程"))
                .andExpect(jsonPath("$.author").value("Eric Matthes"))
                .andExpect(jsonPath("$.publisher").value("人民邮电出版社"))
                .andExpect(jsonPath("$.publishYear").value(2016))
                .andExpect(jsonPath("$.description").value("入门书"))
                .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.totalCopies").value(1))
                .andExpect(jsonPath("$.availableCopies").value(1))
                .andExpect(jsonPath("$.createdAt").value("2026-11-17T02:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-17T02:00:00Z"));
        String barcode = jdbcTemplate.queryForObject("SELECT barcode FROM book_copy WHERE book_id = ?",
                String.class, id);
        assertThat(barcode).matches("C\\d{8}");
    }

    @Test
    @DisplayName("AC-BOOK-01：initialCopies 可取 0 和 50；条码唯一且格式 C+8 位数字")
    void createBookInitialCopiesBounds() throws Exception {
        MvcResult zero = createBook("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"无副本\","
                + "\"author\":\"作者\",\"initialCopies\":0}");
        assertThat(body(zero)).contains("\"totalCopies\":0").contains("\"availableCopies\":0");

        MvcResult fifty = createBook("{\"isbn\":\"" + OTHER_ISBN_13 + "\",\"title\":\"五十副本\","
                + "\"author\":\"作者\",\"initialCopies\":50}");
        long id = idOf(fifty);
        assertThat(body(fifty)).contains("\"totalCopies\":50").contains("\"availableCopies\":50");
        List<String> barcodes = jdbcTemplate.queryForList("SELECT barcode FROM book_copy WHERE book_id = ?",
                String.class, id);
        assertThat(barcodes).hasSize(50).doesNotHaveDuplicates();
        assertThat(barcodes).allSatisfy(b -> assertThat(b).matches("C\\d{8}"));
    }

    @Test
    @DisplayName("AC-BOOK-01：ISBN-10 末位 X 合法，按原样存储（不转 13 位）；小写 x 规范化为大写")
    void createBookIsbn10WithX() throws Exception {
        MvcResult result = createBook(bookJson("0-8044-2957-x", "经典书", "作者"));
        assertThat(body(result)).contains("\"isbn\":\"080442957X\"");
    }

    @Test
    @DisplayName("AC-BOOK-01：字段缺失返回 400 VALIDATION_FAILED 且附 fieldErrors")
    void createBookMissingFields() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'isbn')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'title')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'author')]").exists());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isZero();
    }

    @Test
    @DisplayName("AC-BOOK-01：字段超长返回 400（title 201 / author 101 / publisher 101 / description 2001）")
    void createBookTooLongFields() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(VALID_ISBN_13, "题".repeat(201), "作者")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'title')]").exists());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(VALID_ISBN_13, "书名", "作".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'author')]").exists());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"publisher\":\"" + "社".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'publisher')]").exists());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"description\":\"" + "述".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'description')]").exists());
    }

    @Test
    @DisplayName("AC-BOOK-01〔D4③〕：ISBN 校验位错误返回 400 且 fieldErrors 指向 isbn")
    void createBookBadChecksum() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson("9787115428029", "书名", "作者")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'isbn')]").exists());
        // ISBN-10 校验位错误
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson("0306406153", "书名", "作者")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'isbn')]").exists());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isZero();
    }

    @Test
    @DisplayName("AC-BOOK-01：ISBN 格式非法（长度不符）返回 400")
    void createBookBadIsbnFormat() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson("12345", "书名", "作者")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'isbn')]").exists());
    }

    @Test
    @DisplayName("AC-BOOK-01：ISBN 重复（含不同连字符写法规范化后相同）返回 409 ISBN_DUPLICATE")
    void createBookDuplicateIsbn() throws Exception {
        createBook(bookJson(VALID_ISBN_13, "第一本", "作者"));
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson("978-7-115-42802-8", "第二本", "作者")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISBN_DUPLICATE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-BOOK-01：categoryId 不存在返回 404 CATEGORY_NOT_FOUND；合法 categoryId 正常关联")
    void createBookCategoryResolution() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"categoryId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        Category category = testDataFactory.category("计算机");
        MvcResult ok = createBook("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                + "\"categoryId\":" + category.getId() + "}");
        assertThat(body(ok)).contains("\"category\":{\"id\":" + category.getId() + ",\"name\":\"计算机\"}");
    }

    @Test
    @DisplayName("AC-BOOK-01：publishYear 越界（999/10000）与 initialCopies 越界（-1/51）返回 400；边界值合法")
    void createBookNumericBounds() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"publishYear\":999}"))
                .andExpect(status().isBadRequest());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"publishYear\":10000}"))
                .andExpect(status().isBadRequest());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"initialCopies\":-1}"))
                .andExpect(status().isBadRequest());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"initialCopies\":51}"))
                .andExpect(status().isBadRequest());
        // 边界值 1000/9999 合法
        createBook("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                + "\"publishYear\":1000}");
        asAdmin(put("/api/books/" + jdbcTemplate.queryForObject("SELECT id FROM book", Long.class))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"书名\",\"author\":\"作者\","
                        + "\"publishYear\":9999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishYear").value(9999));
    }

    @Test
    @DisplayName("AC-BOOK-01：字符串字段去首尾空白；可选字段空白按 null 处理")
    void createBookTrimsFields() throws Exception {
        MvcResult result = createBook("{\"isbn\":\" " + VALID_ISBN_13 + " \",\"title\":\"  Python  \","
                + "\"author\":\"  作者  \",\"publisher\":\"   \",\"description\":\"  \"}");
        assertThat(body(result))
                .contains("\"title\":\"Python\"")
                .contains("\"author\":\"作者\"")
                .contains("\"publisher\":null")
                .contains("\"description\":null");
    }

    // ---- AC-BOOK-02 ----

    @Test
    @DisplayName("AC-BOOK-02：管理员修改图书 200，字段与 updatedAt 更新，副本不受影响")
    void updateBook() throws Exception {
        Book book = testDataFactory.book(VALID_ISBN_13, "旧书名", "旧作者", null);
        testDataFactory.copy(book, CopyStatus.AVAILABLE);
        Category category = testDataFactory.category("新分类");
        mutableClock.advance(Duration.ofMinutes(5));
        asAdmin(put("/api/books/" + book.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isbn\":\"" + OTHER_ISBN_13 + "\",\"title\":\"新书名\",\"author\":\"新作者\","
                        + "\"categoryId\":" + category.getId() + ",\"publishYear\":2020}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value(OTHER_ISBN_13))
                .andExpect(jsonPath("$.title").value("新书名"))
                .andExpect(jsonPath("$.author").value("新作者"))
                .andExpect(jsonPath("$.category.id").value(category.getId()))
                .andExpect(jsonPath("$.publishYear").value(2020))
                .andExpect(jsonPath("$.totalCopies").value(1))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-17T02:05:00Z"));
    }

    @Test
    @DisplayName("AC-BOOK-02：修改不存在的图书返回 404 BOOK_NOT_FOUND")
    void updateBookNotFound() throws Exception {
        asAdmin(put("/api/books/999999").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(VALID_ISBN_13, "书名", "作者")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-BOOK-02：修改为其他图书的 ISBN 返回 409；保留自身 ISBN（含连字符写法）允许")
    void updateBookIsbnConflict() throws Exception {
        testDataFactory.book(VALID_ISBN_13, "书一", "作者", null);
        Book second = testDataFactory.book(OTHER_ISBN_13, "书二", "作者", null);
        asAdmin(put("/api/books/" + second.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(VALID_ISBN_13, "书二", "作者")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISBN_DUPLICATE"));
        asAdmin(put("/api/books/" + second.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(bookJson("978-7-115-54483-4", "书二改", "作者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value(OTHER_ISBN_13))
                .andExpect(jsonPath("$.title").value("书二改"));
    }

    @Test
    @DisplayName("AC-BOOK-02：删除无借阅历史的图书返回 204，其副本一并删除")
    void deleteBookWithoutHistory() throws Exception {
        MvcResult created = createBook("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"待删\","
                + "\"author\":\"作者\",\"initialCopies\":3}");
        long id = idOf(created);
        asAdmin(delete("/api/books/" + id)).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_copy", Long.class)).isZero();
    }

    @Test
    @DisplayName("AC-BOOK-02：图书有在借副本时删除返回 409 BOOK_HAS_ACTIVE_LOANS，数据完全不变")
    void deleteBookWithActiveLoan() throws Exception {
        Book book = testDataFactory.book(VALID_ISBN_13, "在借书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        LocalDate today = LocalDate.now(mutableClock);
        Loan loan = testDataFactory.activeLoan(copy, readerFixture.reader(), today, today.plusDays(30),
                adminUser.getId());
        asAdmin(delete("/api/books/" + book.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_HAS_ACTIVE_LOANS"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_copy", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class)).isEqualTo(1);
        assertThat(loanRepository.findById(loan.getId())).isPresent();
    }

    @Test
    @DisplayName("AC-BOOK-02：图书有历史借阅（已归还）时删除返回 409 BOOK_HAS_LOAN_HISTORY，数据完全不变")
    void deleteBookWithLoanHistory() throws Exception {
        Book book = testDataFactory.book(VALID_ISBN_13, "历史书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        LocalDate today = LocalDate.now(mutableClock);
        testDataFactory.returnedLoan(copy, readerFixture.reader(), today.minusDays(40), today.minusDays(10),
                today.minusDays(5), adminUser.getId());
        asAdmin(delete("/api/books/" + book.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_HAS_LOAN_HISTORY"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_copy", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-BOOK-02：删除不存在的图书返回 404 BOOK_NOT_FOUND")
    void deleteBookNotFound() throws Exception {
        asAdmin(delete("/api/books/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    // ---- AC-BOOK-03 ----

    @Test
    @DisplayName("AC-BOOK-03：详情与列表的 totalCopies/availableCopies 正确，借出/归还后立即反映变化")
    void copyCountsReflectBorrowAndReturn() throws Exception {
        MvcResult created = createBook("{\"isbn\":\"" + VALID_ISBN_13 + "\",\"title\":\"计数书\","
                + "\"author\":\"作者\",\"initialCopies\":2}");
        long bookId = idOf(created);
        List<BookCopy> copies = bookCopyRepository.findByBookIdOrderByIdAsc(bookId);
        assertThat(copies).hasSize(2);

        // 初始：2/2（详情与列表一致）
        mockMvc.perform(get("/api/books/" + bookId).session(admin.session()))
                .andExpect(jsonPath("$.totalCopies").value(2))
                .andExpect(jsonPath("$.availableCopies").value(2));
        mockMvc.perform(get("/api/books").param("q", "计数书").session(admin.session()))
                .andExpect(jsonPath("$.items[0].totalCopies").value(2))
                .andExpect(jsonPath("$.items[0].availableCopies").value(2));

        // 借出一个副本（与生产借书相同的原子占用操作；借书接口本身由 T06 验证）
        LocalDate today = LocalDate.now(mutableClock);
        Loan loan = testDataFactory.activeLoan(copies.get(0), readerFixture.reader(), today, today.plusDays(30),
                adminUser.getId());
        mockMvc.perform(get("/api/books/" + bookId).session(admin.session()))
                .andExpect(jsonPath("$.totalCopies").value(2))
                .andExpect(jsonPath("$.availableCopies").value(1));
        mockMvc.perform(get("/api/books").param("q", "计数书").session(admin.session()))
                .andExpect(jsonPath("$.items[0].availableCopies").value(1));

        // 归还（与生产还书相同的原子关闭 + 释放操作）
        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(loanRepository.markReturnedIfActive(loan.getId(), mutableClock.instant(), today,
                    adminUser.getId())).isEqualTo(1);
            assertThat(bookCopyRepository.markAvailableIfBorrowed(copies.get(0).getId(), mutableClock.instant()))
                    .isEqualTo(1);
        });
        mockMvc.perform(get("/api/books/" + bookId).session(admin.session()))
                .andExpect(jsonPath("$.totalCopies").value(2))
                .andExpect(jsonPath("$.availableCopies").value(2));
    }

    @Test
    @DisplayName("AC-BOOK-03：UNAVAILABLE 副本计入 totalCopies 但不计入 availableCopies")
    void unavailableCopyNotCountedAsAvailable() throws Exception {
        Book book = testDataFactory.book(VALID_ISBN_13, "混合状态", "作者", null);
        testDataFactory.copy(book, CopyStatus.AVAILABLE);
        testDataFactory.copy(book, CopyStatus.UNAVAILABLE);
        mockMvc.perform(get("/api/books/" + book.getId()).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCopies").value(2))
                .andExpect(jsonPath("$.availableCopies").value(1));
    }

    @Test
    @DisplayName("AC-BOOK-02：图书详情不存在返回 404 BOOK_NOT_FOUND")
    void detailNotFound() throws Exception {
        mockMvc.perform(get("/api/books/999999").session(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }
}