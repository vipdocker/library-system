package com.example.library.reader;

import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-RDR-01：创建读者（同时创建 READER 账号、系统生成卡号 R%06d、响应不含口令、
 * 用户名重复 409、口令/邮箱/电话/姓名/用户名非法 400、新读者可用初始口令登录）。
 * 附带 AC-AUTH-08 的读者侧断言：哈希以 {bcrypt}$2 存储，响应无口令字段。
 */
class ReaderCreateTest extends ReaderTestBase {

    private static final String VALID_BODY = """
            {"name":"王五","phone":"+86 138-0000","email":"wang@example.com",\
            "username":"wangwu","initialPassword":"Passw0rd123"}""";

    @Test
    @DisplayName("AC-RDR-01：创建读者成功 → 201，账号联动创建，卡号 R%06d，初始口令可登录")
    void createReaderSuccess() throws Exception {
        MvcResult result = asAdmin(post("/api/readers")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("王五"))
                .andExpect(jsonPath("$.phone").value("+86 138-0000"))
                .andExpect(jsonPath("$.email").value("wang@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.username").value("wangwu"))
                .andExpect(jsonPath("$.activeLoanCount").value(0))
                .andExpect(jsonPath("$.overdueLoanCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();
        long id = idOf(result);
        String cardNo = JsonPath.read(body(result), "$.cardNo");
        assertThat(cardNo).matches("R\\d{6}").isEqualTo(String.format("R%06d", id));
        // 响应不含任何口令或哈希字段（AC-AUTH-08 读者侧）
        assertThat(body(result)).doesNotContain("password").doesNotContain("Password").doesNotContain("$2a$");

        // 账号联动：READER 角色、enabled=1、bcrypt 哈希、档案外键与卡号一致
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM app_user WHERE username = 'wangwu'", String.class))
                .isEqualTo("READER");
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM app_user WHERE username = 'wangwu'", Long.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM app_user WHERE username = 'wangwu'",
                String.class)).startsWith("{bcrypt}$2");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = 'wangwu'", Long.class);
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM reader WHERE id = ?", Long.class, id))
                .isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject("SELECT card_no FROM reader WHERE id = ?", String.class, id))
                .isEqualTo(cardNo);

        // 新读者可以用初始口令真实登录，/api/auth/me 带 readerId 和 cardNo
        var session = authTestHelper.loginAs("wangwu", "Passw0rd123");
        mockMvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("READER"))
                .andExpect(jsonPath("$.readerId").value(id))
                .andExpect(jsonPath("$.cardNo").value(cardNo));
    }

    @Test
    @DisplayName("AC-RDR-01：用户名重复（忽略大小写）→ 409 USERNAME_DUPLICATE，数据不变")
    void duplicateUsernameIgnoreCase() throws Exception {
        String body = VALID_BODY.replace("\"wangwu\"", "\"READER-A\"");
        asAdmin(post("/api/readers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_DUPLICATE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest(name = "AC-RDR-01 口令不合规 [{0}] → 400")
    @ValueSource(strings = {"Ab1", "abcdefgh", "12345678", "abcdefgh12345678abcdefgh12345678abcdefgh12345678abcdefgh12345678abc1"})
    @DisplayName("AC-RDR-01：口令不合规（长度/字母/数字）→ 400 VALIDATION_FAILED + fieldErrors[initialPassword]")
    void invalidInitialPassword(String password) throws Exception {
        assertThat(password.length() < 8 || password.length() > 64
                || password.chars().noneMatch(Character::isDigit)
                || password.chars().noneMatch(Character::isLetter)).isTrue();
        String body = VALID_BODY.replace("\"Passw0rd123\"", "\"" + password + "\"");
        asAdmin(post("/api/readers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'initialPassword')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class)).isEqualTo(3);
    }

    static Stream<Arguments> invalidFields() {
        String base = VALID_BODY;
        return Stream.of(
                Arguments.of("email", base.replace("\"wang@example.com\"", "\"not-an-email\"")),
                Arguments.of("phone", base.replace("\"+86 138-0000\"", "\"abc123\"")),
                Arguments.of("phone", base.replace("\"+86 138-0000\"", "\"" + "1".repeat(21) + "\"")),
                Arguments.of("email", base.replace("\"wang@example.com\"", "\"" + "a".repeat(95) + "@b.com\"")),
                Arguments.of("name", base.replace("\"王五\"", "\"   \"")),
                Arguments.of("name", base.replace("\"王五\"", "\"" + "名".repeat(51) + "\"")),
                Arguments.of("username", base.replace("\"wangwu\"", "\"ab\"")),
                Arguments.of("username", base.replace("\"wangwu\"", "\"has space\"")),
                Arguments.of("username", base.replace("\"wangwu\"", "\"" + "u".repeat(33) + "\"")),
                Arguments.of("name", "{\"phone\":null,\"email\":null,\"username\":\"wangwu\","
                        + "\"initialPassword\":\"Passw0rd123\"}"),
                Arguments.of("username", "{\"name\":\"王五\",\"initialPassword\":\"Passw0rd123\"}"),
                Arguments.of("initialPassword", "{\"name\":\"王五\",\"username\":\"wangwu\"}"));
    }

    @ParameterizedTest(name = "AC-RDR-01 字段非法 [{0}] → 400")
    @MethodSource("invalidFields")
    @DisplayName("AC-RDR-01：邮箱/电话/姓名/用户名/必填缺失 → 400 + 对应 fieldErrors，数据不变")
    void invalidFieldRejected(String field, String body) throws Exception {
        asAdmin(post("/api/readers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == '%s')]".formatted(field)).isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-RDR-01：可选字段空白按 null 处理；姓名/用户名 trim 后保存（第 8.1 节）")
    void blankOptionalFieldsAndTrimming() throws Exception {
        String body = """
                {"name":"  王五  ","phone":"   ","email":"","username":" wangwu ","initialPassword":"Passw0rd123"}""";
        MvcResult result = asAdmin(post("/api/readers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("王五"))
                .andExpect(jsonPath("$.phone").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.username").value("wangwu"))
                .andReturn();
        long id = idOf(result);
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM reader WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM reader WHERE id = ?", String.class, id)).isNull();
    }
}