package com.example.library.loan;

import java.time.Instant;
import java.util.List;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 借阅列表与 /me（design-v0.1 第 8.4 节；AC-LOAN-06/07/08、AC-AUTH-05 /me 部分）。
 * 过滤：readerId、status（ACTIVE/OVERDUE/RETURNED/CURRENT）、copyBarcode、q（书名子串）；
 * 分页 + 白名单排序（borrowDate、dueDate，默认 borrowDate,desc）。
 */
class LoanListApiTest extends LoanTestBase {

    /** 标准数据：A 有 active/overdue/returned 各 1 条，B 有 active 1 条。返回 [loan1..loan4]。 */
    private Loan[] standardDataset() {
        BookCopy c1 = newCopy(newBook("Java编程思想"));
        Loan loan1 = activeLoan(c1, readerA, today().minusDays(10), today().plusDays(20));
        BookCopy c2 = newCopy(newBook("javascript高级程序设计"));
        Loan loan2 = activeLoan(c2, readerA, today().minusDays(40), today().minusDays(1));
        BookCopy c3 = newCopy(newBook("深入理解计算机系统"));
        Loan loan3 = testDataFactory.returnedLoan(c3, readerA.reader(), today().minusDays(60),
                today().minusDays(30), today().minusDays(25), adminUser.getId());
        BookCopy c4 = newCopy(newBook("算法导论"));
        Loan loan4 = activeLoan(c4, readerB, today().minusDays(10), today().plusDays(10));
        return new Loan[]{loan1, loan2, loan3, loan4};
    }

    private List<Integer> ids(String json) {
        return JsonPath.read(json, "$.items[*].id");
    }

    @Test
    @DisplayName("AC-LOAN-08：按 readerId 过滤")
    void filterByReaderId() throws Exception {
        Loan[] loans = standardDataset();
        String json = body(asAdmin(get("/api/loans").param("readerId", String.valueOf(readerA.reader().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andReturn());
        assertThat(ids(json)).containsExactlyInAnyOrder(
                loans[0].getId().intValue(), loans[1].getId().intValue(), loans[2].getId().intValue());
        asAdmin(get("/api/loans").param("readerId", String.valueOf(readerB.reader().getId())))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[3].getId()));
    }

    @Test
    @DisplayName("AC-LOAN-08：按 status 过滤（ACTIVE/OVERDUE/RETURNED/CURRENT/全部）")
    void filterByStatus() throws Exception {
        Loan[] loans = standardDataset();
        asAdmin(get("/api/loans").param("status", "ACTIVE"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("ACTIVE"))));
        asAdmin(get("/api/loans").param("status", "OVERDUE"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[1].getId()))
                .andExpect(jsonPath("$.items[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.items[0].overdueDays").value(1));
        asAdmin(get("/api/loans").param("status", "RETURNED"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[2].getId()));
        String current = body(asAdmin(get("/api/loans").param("status", "CURRENT"))
                .andExpect(jsonPath("$.totalItems").value(3)).andReturn());
        assertThat(ids(current)).containsExactlyInAnyOrder(
                loans[0].getId().intValue(), loans[1].getId().intValue(), loans[3].getId().intValue());
        asAdmin(get("/api/loans")).andExpect(jsonPath("$.totalItems").value(4));
    }

    @Test
    @DisplayName("AC-LOAN-08：按副本条码精确过滤")
    void filterByCopyBarcode() throws Exception {
        Loan[] loans = standardDataset();
        String barcode = jdbcTemplate.queryForObject("SELECT barcode FROM book_copy WHERE id = ?",
                String.class, loans[1].getCopy().getId());
        asAdmin(get("/api/loans").param("copyBarcode", barcode))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[1].getId()));
        asAdmin(get("/api/loans").param("copyBarcode", "C99999999"))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    @DisplayName("AC-LOAN-08：q 按书名子串匹配（ASCII 不区分大小写）")
    void filterByTitleSubstring() throws Exception {
        standardDataset();
        asAdmin(get("/api/loans").param("q", "java"))
                .andExpect(jsonPath("$.totalItems").value(2));
        asAdmin(get("/api/loans").param("q", "JAVA"))
                .andExpect(jsonPath("$.totalItems").value(2));
        asAdmin(get("/api/loans").param("q", "算法"))
                .andExpect(jsonPath("$.totalItems").value(1));
        asAdmin(get("/api/loans").param("q", "不存在的书名"))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("AC-LOAN-08：q 中的 % 和 _ 按字面匹配（LikeEscaper）")
    void filterQLiteralWildcards() throws Exception {
        BookCopy c1 = newCopy(newBook("100%算法"));
        activeLoan(c1, readerA, today().minusDays(1), today().plusDays(29));
        BookCopy c2 = newCopy(newBook("100X算法"));
        activeLoan(c2, readerB, today().minusDays(1), today().plusDays(29));
        asAdmin(get("/api/loans").param("q", "0%算"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].book.title").value("100%算法"));
        asAdmin(get("/api/loans").param("q", "%"))
                .andExpect(jsonPath("$.totalItems").value(1));

        BookCopy c3 = newCopy(newBook("A_B测试"));
        testDataFactory.returnedLoan(c3, readerA.reader(), today().minusDays(5), today().plusDays(25),
                today().minusDays(1), adminUser.getId());
        BookCopy c4 = newCopy(newBook("AXB测试"));
        testDataFactory.returnedLoan(c4, readerB.reader(), today().minusDays(5), today().plusDays(25),
                today().minusDays(1), adminUser.getId());
        asAdmin(get("/api/loans").param("q", "A_B"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].book.title").value("A_B测试"));
    }

    @Test
    @DisplayName("AC-LOAN-08：组合过滤（readerId + status + q）")
    void combinedFilters() throws Exception {
        Loan[] loans = standardDataset();
        asAdmin(get("/api/loans")
                        .param("readerId", String.valueOf(readerA.reader().getId()))
                        .param("status", "CURRENT")
                        .param("q", "java"))
                .andExpect(jsonPath("$.totalItems").value(2));
        asAdmin(get("/api/loans")
                        .param("readerId", String.valueOf(readerA.reader().getId()))
                        .param("status", "ACTIVE")
                        .param("q", "java"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[0].getId()));
    }

    @Test
    @DisplayName("AC-LOAN-08：分页（page/size/totalItems/totalPages）")
    void pagination() throws Exception {
        standardDataset();
        asAdmin(get("/api/loans").param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        asAdmin(get("/api/loans").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1));
        asAdmin(get("/api/loans").param("size", "2").param("page", "5"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(4));
    }

    @Test
    @DisplayName("AC-LOAN-08：排序——默认 borrowDate,desc；dueDate,asc 白名单生效")
    void sorting() throws Exception {
        Loan[] loans = standardDataset();
        // 默认 borrowDate,desc（同日按 id desc 保证确定性）：loan4/loan1（-10）→ loan2（-40）→ loan3（-60）
        String json = body(asAdmin(get("/api/loans")).andReturn());
        assertThat(ids(json)).containsExactly(
                loans[3].getId().intValue(), loans[0].getId().intValue(),
                loans[1].getId().intValue(), loans[2].getId().intValue());
        // dueDate,asc：loan3（-30）→ loan2（-1）→ loan4（+10）→ loan1（+20）
        String asc = body(asAdmin(get("/api/loans").param("sort", "dueDate,asc")).andReturn());
        assertThat(ids(asc)).containsExactly(
                loans[2].getId().intValue(), loans[1].getId().intValue(),
                loans[3].getId().intValue(), loans[0].getId().intValue());
        String desc = body(asAdmin(get("/api/loans").param("sort", "borrowDate,asc")).andReturn());
        assertThat(ids(desc)).containsExactly(
                loans[2].getId().intValue(), loans[1].getId().intValue(),
                loans[0].getId().intValue(), loans[3].getId().intValue());
    }

    @Test
    @DisplayName("AC-LOAN-08：非法 status/sort/分页参数 → 400 VALIDATION_FAILED")
    void invalidParams() throws Exception {
        asAdmin(get("/api/loans").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='status')]").exists());
        asAdmin(get("/api/loans").param("sort", "renewCount,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        asAdmin(get("/api/loans").param("sort", "dueDate,sideways"))
                .andExpect(status().isBadRequest());
        asAdmin(get("/api/loans").param("size", "0"))
                .andExpect(status().isBadRequest());
        asAdmin(get("/api/loans").param("size", "101"))
                .andExpect(status().isBadRequest());
        asAdmin(get("/api/loans").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-LOAN-06：集成逾期边界——上海 D 日 23:59:59 为 ACTIVE，D+1 日 00:00（UTC D 16:00）为 OVERDUE")
    void overdueBoundaryWithMutableClock() throws Exception {
        BookCopy copy = newCopy(newBook("逾期边界"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(30), today());
        // 上海当天 23:59:59（UTC 15:59:59）：未逾期
        mutableClock.setInstant(Instant.parse(today() + "T15:59:59Z"));
        asAdmin(get("/api/loans").param("status", "ACTIVE"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].overdueDays").value(0));
        asAdmin(get("/api/loans").param("status", "OVERDUE"))
                .andExpect(jsonPath("$.totalItems").value(0));
        // 上海次日 00:00（UTC 16:00）：逾期 1 天
        mutableClock.setInstant(Instant.parse(today() + "T16:00:00Z"));
        asAdmin(get("/api/loans").param("status", "OVERDUE"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loan.getId()))
                .andExpect(jsonPath("$.items[0].overdueDays").value(1));
        asAdmin(get("/api/loans").param("status", "ACTIVE"))
                .andExpect(jsonPath("$.totalItems").value(0));
        // 逾期后续借被拒（AC-LOAN-05 联动）
        asAdmin(post("/api/loans/" + loan.getId() + "/renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_OVERDUE"));
    }

    @Test
    @DisplayName("AC-LOAN-07/AC-AUTH-05：/me 只返回本人记录；传 readerId 参数也被忽略")
    void myLoansOnlyOwn() throws Exception {
        Loan[] loans = standardDataset();
        String json = body(asReaderA(get("/api/loans/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andReturn());
        assertThat(ids(json)).containsExactlyInAnyOrder(
                loans[0].getId().intValue(), loans[1].getId().intValue(), loans[2].getId().intValue());
        // 试图通过 readerId 参数看他人数据：被忽略
        String withParam = body(asReaderA(get("/api/loans/me")
                        .param("readerId", String.valueOf(readerB.reader().getId())))
                .andExpect(jsonPath("$.totalItems").value(3)).andReturn());
        assertThat(ids(withParam)).doesNotContain(loans[3].getId().intValue());
        // B 只看到自己的 1 条
        asReaderB(get("/api/loans/me"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[3].getId()));
    }

    @Test
    @DisplayName("AC-LOAN-07：/me 的 status=CURRENT/RETURNED 分页签")
    void myLoansStatusTabs() throws Exception {
        Loan[] loans = standardDataset();
        String current = body(asReaderA(get("/api/loans/me").param("status", "CURRENT"))
                .andExpect(jsonPath("$.totalItems").value(2)).andReturn());
        assertThat(ids(current)).containsExactlyInAnyOrder(
                loans[0].getId().intValue(), loans[1].getId().intValue());
        asReaderA(get("/api/loans/me").param("status", "RETURNED"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(loans[2].getId()))
                .andExpect(jsonPath("$.items[0].returnDate").value(today().minusDays(25).toString()));
        asReaderA(get("/api/loans/me").param("status", "OVERDUE"))
                .andExpect(jsonPath("$.items[0].overdueDays").value(1));
        asReaderA(get("/api/loans/me").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-LOAN-07：/me 分页生效")
    void myLoansPagination() throws Exception {
        standardDataset();
        asReaderA(get("/api/loans/me").param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("AC-LOAN-05/08：管理员列表 renewable——可续借 true；逾期/已还/次数用尽/读者停用/另有逾期 false")
    void renewableInAdminList() throws Exception {
        Loan[] loans = standardDataset();
        // 停用读者的在借
        ReaderFixture disabled = testDataFactory.reader("loan-list-disabled", READER_PASSWORD, "停用列表",
                ReaderStatus.DISABLED);
        BookCopy dc = newCopy(newBook("停用列表之书"));
        Loan disabledLoan = activeLoan(dc, disabled, today().minusDays(5), today().plusDays(25));

        // 续借前：B 无逾期，loan4 可续借；A 有逾期 loan2，故 loan1 也不可续借（第 9.4 节第 5 步）
        java.util.Map<Integer, Boolean> before = renewableById(
                body(asAdmin(get("/api/loans").param("size", "100")).andReturn()));
        assertThat(before.get(loans[3].getId().intValue())).isTrue();    // 可续借
        assertThat(before.get(loans[0].getId().intValue())).isFalse();   // 读者另有逾期
        assertThat(before.get(loans[1].getId().intValue())).isFalse();   // 逾期
        assertThat(before.get(loans[2].getId().intValue())).isFalse();   // 已还
        assertThat(before.get(disabledLoan.getId().intValue())).isFalse(); // 读者停用

        // 次数用尽：续借 B 的 loan4 后 renewable=false
        asAdmin(post("/api/loans/" + loans[3].getId() + "/renew")).andExpect(status().isOk());
        java.util.Map<Integer, Boolean> after = renewableById(
                body(asAdmin(get("/api/loans").param("size", "100")).andReturn()));
        assertThat(after.get(loans[3].getId().intValue())).isFalse();
    }

    private static java.util.Map<Integer, Boolean> renewableById(String json) {
        java.util.Map<Integer, Boolean> renewable = new java.util.HashMap<>();
        List<java.util.Map<String, Object>> items = JsonPath.read(json, "$.items[*]");
        for (java.util.Map<String, Object> item : items) {
            renewable.put(((Number) item.get("id")).intValue(), (Boolean) item.get("renewable"));
        }
        return renewable;
    }

    @Test
    @DisplayName("AC-LOAN-07/D2=false：/me 中即使业务上可续借，renewable 也为 false（调用者是读者且开关关闭）")
    void renewableFalseForReaderWhenD2Disabled() throws Exception {
        BookCopy copy = newCopy(newBook("读者renewable"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        asReaderA(get("/api/loans/me"))
                .andExpect(jsonPath("$.items[0].id").value(loan.getId()))
                .andExpect(jsonPath("$.items[0].renewable").value(false));
        // 同一借阅在管理员列表中 renewable=true
        asAdmin(get("/api/loans"))
                .andExpect(jsonPath("$.items[0].renewable").value(true));
    }
}