package com.example.library.reader;

import java.util.List;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.support.TestDataFactory.ReaderFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-RDR-04：分页检索读者（姓名子串、卡号精确、用户名精确忽略大小写、状态过滤、
 * 白名单排序 name/cardNo/createdAt），activeLoanCount 与 overdueLoanCount 正确。
 * 逾期口径：未还且 due_date < today（MutableClock 固定 2026-11-17，Asia/Shanghai）。
 */
class ReaderSearchTest extends ReaderTestBase {

    private ReaderFixture readerC;

    @BeforeEach
    void seedSearchData() {
        readerC = testDataFactory.reader("reader-c", READER_PASSWORD, "张小三", ReaderStatus.DISABLED);
        Book book = testDataFactory.book("9787115428028", "检索图书", "作者", null);
        BookCopy copy1 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy copy2 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy copy3 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        // reader-a：1 条未逾期在借 + 1 条逾期未还 + 1 条已归还历史
        testDataFactory.activeLoan(copy1, readerA.reader(), today().minusDays(5), today().plusDays(10),
                adminUser.getId());
        testDataFactory.activeLoan(copy2, readerA.reader(), today().minusDays(40), today().minusDays(1),
                adminUser.getId());
        testDataFactory.returnedLoan(copy3, readerA.reader(), today().minusDays(40), today().minusDays(10),
                today().minusDays(12), adminUser.getId());
    }

    private MvcResult list(String... params) throws Exception {
        var request = get("/api/readers");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return asAdmin(request).andExpect(status().isOk()).andReturn();
    }

    @Test
    @DisplayName("AC-RDR-04：列表返回全部读者，activeLoanCount/overdueLoanCount 正确（已归还不计入）")
    void listWithLoanCounts() throws Exception {
        MvcResult result = list();
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isEqualTo(3);
        List<Object> itemA = JsonPath.read(body(result), "$.items[?(@.id == %d)]".formatted(readerA.reader().getId()));
        assertThat(itemA).hasSize(1);
        assertThat((Integer) JsonPath.read(itemA.get(0), "$.activeLoanCount")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(itemA.get(0), "$.overdueLoanCount")).isEqualTo(1);
        assertThat((String) JsonPath.read(itemA.get(0), "$.username")).isEqualTo("reader-a");
        List<Object> itemB = JsonPath.read(body(result), "$.items[?(@.id == %d)]".formatted(readerB.reader().getId()));
        assertThat((Integer) JsonPath.read(itemB.get(0), "$.activeLoanCount")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(itemB.get(0), "$.overdueLoanCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("AC-RDR-04：姓名按子串匹配")
    void searchByNameSubstring() throws Exception {
        MvcResult result = list("q", "张");
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isEqualTo(2);
        List<String> names = JsonPath.read(body(result), "$.items[*].name");
        assertThat(names).containsExactlyInAnyOrder("张三", "张小三");
    }

    @Test
    @DisplayName("AC-RDR-04：卡号按精确匹配（子串不命中）")
    void searchByCardNoExact() throws Exception {
        String cardNo = readerA.reader().getCardNo();
        MvcResult exact = list("q", cardNo);
        assertThat((Integer) JsonPath.read(exact.getResponse().getContentAsString(), "$.totalItems")).isEqualTo(1);
        assertThat((String) JsonPath.read(body(exact), "$.items[0].cardNo")).isEqualTo(cardNo);
        // 卡号的一部分不是精确匹配，也不匹配姓名/用户名 → 空结果
        MvcResult partial = list("q", cardNo.substring(1));
        assertThat((Integer) JsonPath.read(body(partial), "$.totalItems")).isEqualTo(0);
    }

    @Test
    @DisplayName("AC-RDR-04：用户名按精确匹配（忽略大小写；子串不命中）")
    void searchByUsernameExactIgnoreCase() throws Exception {
        for (String q : new String[]{"READER-A", "reader-a"}) {
            MvcResult result = list("q", q);
            assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isEqualTo(1);
            assertThat((String) JsonPath.read(body(result), "$.items[0].username")).isEqualTo("reader-a");
        }
        MvcResult partial = list("q", "reader");
        assertThat((Integer) JsonPath.read(body(partial), "$.totalItems")).isEqualTo(0);
    }

    @Test
    @DisplayName("AC-RDR-04：状态过滤生效，可与 q 组合；非法 status → 400")
    void statusFilter() throws Exception {
        assertThat((Integer) JsonPath.read(body(list("status", "ACTIVE")), "$.totalItems")).isEqualTo(2);
        MvcResult disabled = list("status", "DISABLED");
        assertThat((Integer) JsonPath.read(body(disabled), "$.totalItems")).isEqualTo(1);
        assertThat((String) JsonPath.read(body(disabled), "$.items[0].name")).isEqualTo("张小三");
        assertThat((Integer) JsonPath.read(body(list("q", "张", "status", "ACTIVE")), "$.totalItems")).isEqualTo(1);
        asAdmin(get("/api/readers").param("status", "SUSPENDED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("AC-RDR-04：分页 page/size/totalItems/totalPages 正确")
    void pagination() throws Exception {
        MvcResult page0 = list("page", "0", "size", "2");
        assertThat((Integer) JsonPath.read(body(page0), "$.items.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body(page0), "$.totalItems")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body(page0), "$.totalPages")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body(page0), "$.page")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(body(page0), "$.size")).isEqualTo(2);
        MvcResult page1 = list("page", "1", "size", "2");
        assertThat((Integer) JsonPath.read(body(page1), "$.items.length()")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-RDR-04：白名单排序 name/cardNo/createdAt 生效")
    void sortWhitelistAccepted() throws Exception {
        MvcResult byCard = list("sort", "cardNo,asc");
        List<String> cardNos = JsonPath.read(body(byCard), "$.items[*].cardNo");
        assertThat(cardNos).isSorted();
        MvcResult byCardDesc = list("sort", "cardNo,desc");
        List<String> desc = JsonPath.read(body(byCardDesc), "$.items[*].cardNo");
        assertThat(desc).containsExactlyElementsOf(cardNos.reversed());
        list("sort", "createdAt,desc");
        list("sort", "name,asc");
    }

    @ParameterizedTest(name = "AC-RDR-04 非法排序 [{0}] → 400")
    @ValueSource(strings = {"password,asc", "name", "name,up", "status,asc"})
    @DisplayName("AC-RDR-04：白名单外或格式非法的 sort → 400 VALIDATION_FAILED")
    void invalidSortRejected(String sort) throws Exception {
        asAdmin(get("/api/readers").param("sort", sort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("AC-RDR-04：非法分页参数 → 400（page<0、size<1、size>100）")
    void invalidPagingRejected() throws Exception {
        asAdmin(get("/api/readers").param("page", "-1")).andExpect(status().isBadRequest());
        asAdmin(get("/api/readers").param("size", "0")).andExpect(status().isBadRequest());
        asAdmin(get("/api/readers").param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-RDR-04：q 中的 % 与 _ 按字面匹配（LikeEscaper）")
    void likeMetacharactersAreLiteral() throws Exception {
        testDataFactory.reader("reader-pct", READER_PASSWORD, "100%张", ReaderStatus.ACTIVE);
        testDataFactory.reader("reader-plain", READER_PASSWORD, "100x张", ReaderStatus.ACTIVE);
        MvcResult result = list("q", "100%");
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isEqualTo(1);
        assertThat((String) JsonPath.read(body(result), "$.items[0].name")).isEqualTo("100%张");
    }

    @Test
    @DisplayName("AC-RDR-04：读者详情返回正确计数与用户名；不存在 → 404 READER_NOT_FOUND")
    void detailWithCounts() throws Exception {
        asAdmin(get("/api/readers/" + readerA.reader().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(readerA.reader().getId()))
                .andExpect(jsonPath("$.cardNo").value(readerA.reader().getCardNo()))
                .andExpect(jsonPath("$.username").value("reader-a"))
                .andExpect(jsonPath("$.activeLoanCount").value(2))
                .andExpect(jsonPath("$.overdueLoanCount").value(1));
        asAdmin(get("/api/readers/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READER_NOT_FOUND"));
    }
}