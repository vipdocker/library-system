package com.example.library.catalog;

import java.time.Duration;
import java.util.List;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.Category;
import com.example.library.domain.enums.CopyStatus;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-SRCH-01：书名/作者子串（ASCII 不区分大小写）、ISBN 规范化精确匹配、field=all、
 * 分类过滤、仅可借过滤、分页 totalItems、空结果 200、%/_/\ 字面匹配、q 长度与排序白名单校验。
 */
class BookSearchTest extends CatalogTestBase {

    private Book b1;
    private Book b2;
    private Book b3;
    private Book b4;
    private Book b5;
    private Book b6;
    private Category computer;
    private Category programming;

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.library.domain.repository.BookRepository bookRepository;

    @BeforeEach
    void seedBooks() {
        computer = testDataFactory.category("计算机");
        programming = testDataFactory.category("编程");
        // 每本间隔 1 分钟，便于 createdAt 排序断言
        b1 = testDataFactory.book("9787115428028", "Python Crash Course", "Eric Matthes", computer.getId());
        b1.setPublishYear(2016);
        mutableClock.advance(Duration.ofMinutes(1));
        b2 = testDataFactory.book("9787115544834", "python Tricks", "Dan Bader", computer.getId());
        b2.setPublishYear(2018);
        mutableClock.advance(Duration.ofMinutes(1));
        b3 = testDataFactory.book("9780306406157", "Java Programming", "John Smith", programming.getId());
        b3.setPublishYear(2019);
        mutableClock.advance(Duration.ofMinutes(1));
        b4 = testDataFactory.book("0306406152", "100% Java", "a_b writer", null);
        mutableClock.advance(Duration.ofMinutes(1));
        b5 = testDataFactory.book("080442957X", "Deep Learning", "Ian Goodfellow", null);
        mutableClock.advance(Duration.ofMinutes(1));
        b6 = testDataFactory.book(isbn13("978711542806"), "Back\\Slash", "Slash Author", null);
        // 工厂返回的实体已脱管：publishYear 需显式保存
        bookRepository.saveAll(List.of(b1, b2, b3));
        // 副本：b1 有 1 可借 + 1 不可用；b2 无副本；b3/b4/b5 各 1 可借；b6 无副本
        testDataFactory.copy(b1, CopyStatus.AVAILABLE);
        testDataFactory.copy(b1, CopyStatus.UNAVAILABLE);
        testDataFactory.copy(b3, CopyStatus.AVAILABLE);
        testDataFactory.copy(b4, CopyStatus.AVAILABLE);
        testDataFactory.copy(b5, CopyStatus.AVAILABLE);
    }

    private MvcResult search(String... params) throws Exception {
        var request = get("/api/books").session(reader.session());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private List<Long> itemIds(MvcResult result) throws Exception {
        List<Number> ids = JsonPath.read(body(result), "$.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    @Test
    @DisplayName("AC-SRCH-01：书名子串匹配，ASCII 不区分大小写")
    void titleSubstringCaseInsensitive() throws Exception {
        MvcResult result = search("q", "PYTH", "field", "title");
        assertThat(itemIds(result)).containsExactly(b1.getId(), b2.getId());
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-SRCH-01：作者子串匹配，ASCII 不区分大小写")
    void authorSubstringCaseInsensitive() throws Exception {
        MvcResult result = search("q", "matthes", "field", "author");
        assertThat(itemIds(result)).containsExactly(b1.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：ISBN 带或不带连字符都精确命中；小写 x 规范化；ISBN-10 原样匹配")
    void isbnExactMatch() throws Exception {
        assertThat(itemIds(search("q", "978-7-115-42802-8", "field", "isbn"))).containsExactly(b1.getId());
        assertThat(itemIds(search("q", "9787115428028", "field", "isbn"))).containsExactly(b1.getId());
        assertThat(itemIds(search("q", "0-8044-2957-x", "field", "isbn"))).containsExactly(b5.getId());
        assertThat(itemIds(search("q", "0306406152", "field", "isbn"))).containsExactly(b4.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：field=isbn 规范化后长度不符 → 200 空结果，不报错；合法格式但无命中也为空")
    void isbnInvalidFormatReturnsEmpty() throws Exception {
        MvcResult result = search("q", "123", "field", "isbn");
        assertThat(itemIds(result)).isEmpty();
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isZero();
        // 格式合法但校验位错误：检索不做校验位检查，精确匹配无命中 → 空
        assertThat(itemIds(search("q", "9787115428029", "field", "isbn"))).isEmpty();
    }

    @Test
    @DisplayName("AC-SRCH-01：field=all 同时匹配书名、作者和 ISBN；缺省 field 即 all")
    void allFieldMatchesTitleAuthorIsbn() throws Exception {
        assertThat(itemIds(search("q", "python", "field", "all"))).containsExactly(b1.getId(), b2.getId());
        assertThat(itemIds(search("q", "Matthes", "field", "all"))).containsExactly(b1.getId());
        assertThat(itemIds(search("q", "9787115428028", "field", "all"))).containsExactly(b1.getId());
        // 不传 field：默认 all
        assertThat(itemIds(search("q", "python"))).containsExactly(b1.getId(), b2.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：分类过滤生效，可与 q 组合")
    void categoryFilter() throws Exception {
        assertThat(itemIds(search("categoryId", String.valueOf(computer.getId()))))
                .containsExactly(b1.getId(), b2.getId());
        assertThat(itemIds(search("categoryId", String.valueOf(computer.getId()), "q", "tricks")))
                .containsExactly(b2.getId());
        assertThat(itemIds(search("categoryId", "999999"))).isEmpty();
    }

    @Test
    @DisplayName("AC-SRCH-01：availableOnly=true 只返回有 AVAILABLE 副本的图书")
    void availableOnlyFilter() throws Exception {
        assertThat(itemIds(search("availableOnly", "true")))
                .containsExactly(b4.getId(), b5.getId(), b3.getId(), b1.getId());
        // false / 缺省：全部
        assertThat(itemIds(search("availableOnly", "false"))).hasSize(6);
    }

    @Test
    @DisplayName("AC-SRCH-01：分页 totalItems/totalPages 正确，翻页内容正确")
    void pagination() throws Exception {
        MvcResult page0 = search("size", "2", "page", "0");
        assertThat((Integer) JsonPath.read(body(page0), "$.totalItems")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(body(page0), "$.totalPages")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body(page0), "$.page")).isZero();
        assertThat((Integer) JsonPath.read(body(page0), "$.size")).isEqualTo(2);
        assertThat(itemIds(page0)).containsExactly(b4.getId(), b6.getId());
        MvcResult page2 = search("size", "2", "page", "2");
        assertThat(itemIds(page2)).containsExactly(b1.getId(), b2.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：没有结果时返回 200 和空 items")
    void emptyResult() throws Exception {
        MvcResult result = search("q", "zzz-不存在的书名");
        assertThat(itemIds(result)).isEmpty();
        assertThat((Integer) JsonPath.read(body(result), "$.totalItems")).isZero();
        assertThat((Integer) JsonPath.read(body(result), "$.totalPages")).isZero();
    }

    @Test
    @DisplayName("AC-SRCH-01：% 和 _ 按字面字符匹配（LikeEscaper + ESCAPE）")
    void percentAndUnderscoreLiteral() throws Exception {
        // 未转义时 "%" 会匹配全部 6 本；转义后只匹配含字面 % 的 b4
        assertThat(itemIds(search("q", "%", "field", "title"))).containsExactly(b4.getId());
        assertThat(itemIds(search("q", "100%", "field", "title"))).containsExactly(b4.getId());
        // 未转义时 "_" 匹配任意单字符（全部作者）；转义后只匹配含字面 _ 的 b4
        assertThat(itemIds(search("q", "_", "field", "author"))).containsExactly(b4.getId());
        assertThat(itemIds(search("q", "a_b", "field", "author"))).containsExactly(b4.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：反斜杠按字面字符匹配")
    void backslashLiteral() throws Exception {
        assertThat(itemIds(search("q", "\\", "field", "title"))).containsExactly(b6.getId());
        assertThat(itemIds(search("q", "Back\\Slash", "field", "title"))).containsExactly(b6.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：q 超过 100 字符返回 400；恰好 100 字符合法")
    void qLengthLimit() throws Exception {
        mockMvc.perform(get("/api/books").param("q", "a".repeat(101)).session(reader.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/books").param("q", "a".repeat(100)).session(reader.session()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-SRCH-01：q 为空/空白时列出全部")
    void emptyQListsAll() throws Exception {
        assertThat(itemIds(search())).hasSize(6);
        assertThat(itemIds(search("q", ""))).hasSize(6);
        assertThat(itemIds(search("q", "   "))).hasSize(6);
    }

    @Test
    @DisplayName("AC-SRCH-01：非法 field 返回 400；非法参数类型返回 400")
    void invalidFieldAndParamTypes() throws Exception {
        mockMvc.perform(get("/api/books").param("field", "publisher").session(reader.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/books").param("availableOnly", "notabool").session(reader.session()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/books").param("categoryId", "abc").session(reader.session()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-SRCH-01：默认排序 title,asc；白名单内 author/publishYear/createdAt 排序生效")
    void sortWhitelistAccepted() throws Exception {
        // 默认 title,asc（二进制序）：100% Java < Back\Slash < Deep Learning < Java Programming < Python... < python...
        assertThat(itemIds(search())).containsExactly(
                b4.getId(), b6.getId(), b5.getId(), b3.getId(), b1.getId(), b2.getId());
        // author,desc：a_b writer 最大
        MvcResult byAuthor = search("sort", "author,desc");
        assertThat(itemIds(byAuthor).get(0)).isEqualTo(b4.getId());
        // publishYear,asc：非空年份按升序（null 年份不参与该断言）
        MvcResult byYear = search("sort", "publishYear,asc");
        List<Integer> years = JsonPath.read(body(byYear), "$.items[*].publishYear");
        assertThat(years.stream().filter(java.util.Objects::nonNull).toList())
                .containsExactly(2016, 2018, 2019);
        // createdAt,desc：最后创建的 b6 在最前
        assertThat(itemIds(search("sort", "createdAt,desc")).get(0)).isEqualTo(b6.getId());
        // title,desc
        assertThat(itemIds(search("sort", "title,desc")).get(0)).isEqualTo(b2.getId());
    }

    @Test
    @DisplayName("AC-SRCH-01：白名单外排序字段、非法方向、缺方向返回 400")
    void sortWhitelistRejected() throws Exception {
        for (String badSort : new String[]{"isbn,asc", "title,sideways", "title", "id,asc"}) {
            mockMvc.perform(get("/api/books").param("sort", badSort).session(reader.session()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("AC-SRCH-01：page/size 越界返回 400")
    void pageValidation() throws Exception {
        mockMvc.perform(get("/api/books").param("page", "-1").session(reader.session()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/books").param("size", "0").session(reader.session()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/books").param("size", "101").session(reader.session()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-SRCH-01/AC-BOOK-03：列表项含 category 引用与 totalCopies/availableCopies；无分类为 null")
    void summariesContainCategoryAndCounts() throws Exception {
        MvcResult result = search("q", "Python Crash Course", "field", "title");
        assertThat(body(result))
                .contains("\"category\":{\"id\":" + computer.getId() + ",\"name\":\"计算机\"}")
                .contains("\"totalCopies\":2")
                .contains("\"availableCopies\":1");
        MvcResult noCategory = search("q", "100% Java", "field", "title");
        assertThat((Object) JsonPath.read(body(noCategory), "$.items[0].category")).isNull();
        assertThat((Integer) JsonPath.read(body(noCategory), "$.items[0].totalCopies")).isEqualTo(1);
    }
}