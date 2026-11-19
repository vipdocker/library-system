package com.example.library.catalog;

import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-CAT-01：分类新建/修改/删除、任何已登录用户可列出、重名（忽略大小写）409、
 * 删除被引用分类 409 CATEGORY_IN_USE、bookCount 正确。
 */
class CategoryApiTest extends CatalogTestBase {

    private MvcResult createCategory(String json) throws Exception {
        return asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    @DisplayName("AC-CAT-01：管理员新建分类 201，name 去首尾空白，bookCount=0")
    void createCategory() throws Exception {
        MvcResult result = createCategory("{\"name\":\"  计算机  \",\"description\":\"编程与系统\"}");
        long id = idOf(result);
        assertThat(id).isPositive();
        mockMvc.perform(get("/api/categories").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].name").value("计算机"))
                .andExpect(jsonPath("$[0].description").value("编程与系统"))
                .andExpect(jsonPath("$[0].bookCount").value(0));
    }

    @Test
    @DisplayName("AC-CAT-01：任何已登录用户（读者）都能列出分类；按名称排序且 bookCount 正确")
    void listCategoriesAsReaderSortedWithBookCount() throws Exception {
        Category beta = testDataFactory.category("Beta");
        testDataFactory.category("alpha");
        testDataFactory.category("Gamma");
        testDataFactory.book(isbn13("978711542801"), "书一", "作者", beta.getId());
        testDataFactory.book(isbn13("978711542802"), "书二", "作者", beta.getId());

        asReader(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                // NOCASE 排序：alpha < Beta < Gamma
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[0].bookCount").value(0))
                .andExpect(jsonPath("$[1].name").value("Beta"))
                .andExpect(jsonPath("$[1].bookCount").value(2))
                .andExpect(jsonPath("$[2].name").value("Gamma"))
                .andExpect(jsonPath("$[2].bookCount").value(0));
    }

    @Test
    @DisplayName("AC-CAT-01：新建重名分类（忽略大小写）返回 409 CATEGORY_NAME_DUPLICATE")
    void createDuplicateNameCaseInsensitive() throws Exception {
        createCategory("{\"name\":\"Sci-Fi\"}");
        asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SCI-FI\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM category", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-CAT-01：新建分类字段缺失/超长返回 400 VALIDATION_FAILED 且带 fieldErrors")
    void createValidationErrors() throws Exception {
        // name 缺失
        asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"只有描述\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists());
        // name 51 字符
        asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "名".repeat(51) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists());
        // description 201 字符
        asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"合法\",\"description\":\"" + "描".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'description')]").exists());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM category", Long.class)).isZero();
    }

    @Test
    @DisplayName("AC-CAT-01：可选 description 空白按 null 处理；name 边界 1/50 字符合法")
    void optionalBlankDescriptionAndNameBounds() throws Exception {
        asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"短\",\"description\":\"   \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()));
        asAdmin(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "名".repeat(50) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-CAT-01：管理员修改分类 200，bookCount 保持正确")
    void updateCategory() throws Exception {
        Category category = testDataFactory.category("旧名");
        testDataFactory.book(isbn13("978711542801"), "书", "作者", category.getId());
        mutableClock.advance(java.time.Duration.ofMinutes(5));
        asAdmin(put("/api/categories/" + category.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名\",\"description\":\"新描述\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(category.getId()))
                .andExpect(jsonPath("$.name").value("新名"))
                .andExpect(jsonPath("$.description").value("新描述"))
                .andExpect(jsonPath("$.bookCount").value(1));
    }

    @Test
    @DisplayName("AC-CAT-01：修改为其他分类的名称（忽略大小写）返回 409；改成自身名称的大小写变体允许")
    void updateDuplicateName() throws Exception {
        testDataFactory.category("Sci-Fi");
        Category other = testDataFactory.category("History");
        asAdmin(put("/api/categories/" + other.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sci-fi\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATE"));
        // 自身名称大小写变体：不算重复
        asAdmin(put("/api/categories/" + other.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HISTORY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HISTORY"));
    }

    @Test
    @DisplayName("AC-CAT-01：修改不存在的分类返回 404 CATEGORY_NOT_FOUND")
    void updateNotFound() throws Exception {
        asAdmin(put("/api/categories/999999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"不存在\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-CAT-01：删除未被引用的分类返回 204")
    void deleteCategory() throws Exception {
        Category category = testDataFactory.category("待删除");
        asAdmin(delete("/api/categories/" + category.getId()))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM category", Long.class)).isZero();
    }

    @Test
    @DisplayName("AC-CAT-01：删除仍被图书引用的分类返回 409 CATEGORY_IN_USE，数据不变")
    void deleteInUseCategory() throws Exception {
        Category category = testDataFactory.category("被引用");
        Book book = testDataFactory.book(isbn13("978711542801"), "书", "作者", category.getId());
        asAdmin(delete("/api/categories/" + category.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM category", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT category_id FROM book WHERE id = ?", Long.class, book.getId()))
                .isEqualTo(category.getId());
    }

    @Test
    @DisplayName("AC-CAT-01：删除不存在的分类返回 404 CATEGORY_NOT_FOUND")
    void deleteNotFound() throws Exception {
        asAdmin(delete("/api/categories/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }
}