package com.example.library.dunning;

import java.time.Instant;
import java.time.LocalDate;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.*;
import com.example.library.security.LibraryUserPrincipal;
import com.example.library.support.IntegrationTestBase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DunningApiTest extends IntegrationTestBase {
    private Loan loan;
    private LibraryUserPrincipal admin;
    private LibraryUserPrincipal reader;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    @BeforeEach void fixture() {
        var a = testDataFactory.admin("admin", "Admin@12345");
        var r = testDataFactory.reader("reader", "Reader@123", "读者", ReaderStatus.ACTIVE);
        testDataFactory.reader("other", "Reader@123", "其他", ReaderStatus.ACTIVE);
        var book = testDataFactory.book("9787115428028", "测试书名", "作者", null);
        loan = testDataFactory.activeLoan(testDataFactory.copy(book, CopyStatus.AVAILABLE), r.reader(),
                TODAY.minusDays(40), TODAY.minusDays(10), a.getId());
        admin = testDataFactory.principal("admin");
        reader = testDataFactory.principal("reader");
    }

    private String path(long id) { return "/api/loans/" + id + "/dunning-records"; }
    private RequestPostProcessor cookieCsrf() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
    private ResultActions register(long id, String json) throws Exception {
        return mockMvc.perform(post(path(id)).with(user(admin)).with(cookieCsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
    private void emptyWrites() {
        assertThat(count("dunning_record")).isZero();
        assertThat(count("notification")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"PHONE", "IN_PERSON", "IN_APP", "OTHER"})
    @DisplayName("AC-DUN-01: each method creates one private history record and one real fixed notice")
    void validMethods(String method) throws Exception {
        register(loan.getId(), "{\"method\":\"" + method + "\",\"note\":\" 管理员私密备注 \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.loanId").value(loan.getId()))
                .andExpect(jsonPath("$.method").value(method))
                .andExpect(jsonPath("$.note").value("管理员私密备注"))
                .andExpect(jsonPath("$.operator.id").value(admin.getUserId()))
                .andExpect(jsonPath("$.operator.username").value("admin"))
                .andExpect(jsonPath("$.notificationId").value(1))
                .andExpect(jsonPath("$.createdAt").value(mutableClock.instant().toString()));
        assertThat(count("dunning_record")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT operator_id FROM dunning_record", Long.class))
                .isEqualTo(admin.getUserId());
        mockMvc.perform(get("/api/notifications/me").with(user(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("DUNNING"))
                .andExpect(jsonPath("$.items[0].content").value("您借阅的《测试书名》（条码：C00000001），应还日为 2026-11-07，图书馆提醒您及时归还。"))
                .andExpect(content().string(not(containsString("管理员私密备注"))))
                .andExpect(content().string(not(containsString("operator"))))
                .andExpect(content().string(not(containsString("notificationId"))));
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("other"))))
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/auth/csrf")).andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @ParameterizedTest @ValueSource(strings = {"", ",\"note\":null", ",\"note\":\"\"", ",\"note\":\"   \""})
    @DisplayName("AC-DUN-01: absent null empty and whitespace notes persist as null")
    void emptyNotes(String note) throws Exception {
        register(loan.getId(), "{\"method\":\"PHONE\"" + note + "}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.note").value(nullValue()));
        assertThat(jdbcTemplate.queryForObject("SELECT note FROM dunning_record", String.class)).isNull();
    }
    @Test @DisplayName("AC-DUN-01: 500 Chinese characters accepted, 501 rejected without residual writes")
    void noteBoundary() throws Exception {
        register(loan.getId(), "{\"method\":\"OTHER\",\"note\":\"" + "字".repeat(501) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        emptyWrites();
        register(loan.getId(), "{\"method\":\"OTHER\",\"note\":\"" + "字".repeat(500) + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.note").value("字".repeat(500)));
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "{", "{\"method\":null}",
            "{\"method\":\"EMAIL\"}", "{\"method\":\"phone\"}", "{\"method\":0}",
            "{\"method\":{}}", "{\"method\":\"PHONE\",\"note\":12}",
            "{\"method\":\"PHONE\",\"note\":{}}", "{\"method\":\"PHONE\",\"operatorId\":2}"})
    @DisplayName("AC-DUN-01/02: malformed body unknown fields and invalid enum/types are rejected atomically")
    void invalidBodies(String json) throws Exception {
        register(loan.getId(), json).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        emptyWrites();
    }
    @Test @DisplayName("AC-DUN-02: missing loan gives 404 and no residual writes")
    void missingLoan() throws Exception {
        register(999, "{\"method\":\"PHONE\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        mockMvc.perform(get(path(999)).with(user(admin))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));
        emptyWrites();
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    @DisplayName("AC-DUN-02: due today or future is not overdue")
    void notOverdue(int days) throws Exception {
        jdbcTemplate.update("UPDATE loan SET due_date=? WHERE id=?", TODAY.plusDays(days).toString(), loan.getId());
        register(loan.getId(), "{\"method\":\"PHONE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_OVERDUE"));
        emptyWrites();
    }
    @Test @DisplayName("AC-DUN-01/02: Shanghai midnight determines overdue eligibility")
    void midnight() throws Exception {
        jdbcTemplate.update("UPDATE loan SET due_date=? WHERE id=?", TODAY.toString(), loan.getId());
        mutableClock.setInstant(Instant.parse("2026-11-17T15:59:59Z"));
        register(loan.getId(), "{\"method\":\"PHONE\"}").andExpect(status().isConflict());
        emptyWrites();
        mutableClock.setInstant(Instant.parse("2026-11-17T16:00:00Z"));
        register(loan.getId(), "{\"method\":\"PHONE\"}").andExpect(status().isCreated());
    }
    @ParameterizedTest @ValueSource(strings = {"id", "barcode"})
    @DisplayName("AC-DUN-03 AC-LOAN-04: both real return endpoints close the case at returnedAt and preserve history")
    void bothReturnPaths(String mode) throws Exception {
        register(loan.getId(), "{\"method\":\"PHONE\"}").andExpect(status().isCreated());
        Instant returnedAt = Instant.parse("2026-11-17T08:23:45.123Z");
        mutableClock.setInstant(returnedAt);
        var request = mode.equals("id") ? post("/api/loans/" + loan.getId() + "/return")
                : post("/api/loans/return-by-barcode").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"copyBarcode\":\"C00000001\"}");
        mockMvc.perform(request.with(user(admin)).with(cookieCsrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));
        mockMvc.perform(get(path(loan.getId())).with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(returnedAt.toString()))
                .andExpect(jsonPath("$.closeReason").value("RETURNED"))
                .andExpect(jsonPath("$.records.length()").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT returned_at FROM loan WHERE id=?", Long.class, loan.getId()))
                .isEqualTo(returnedAt.toEpochMilli());
        register(loan.getId(), "{\"method\":\"OTHER\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_RETURNED"));
        assertThat(count("dunning_record")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        mockMvc.perform(get("/api/dunning-cases").param("status", "OPEN").with(user(admin)))
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/dunning-cases").param("status", "CLOSED").with(user(admin)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].closedAt").value(returnedAt.toString()));
    }
    @Test @DisplayName("AC-DUN-02: already returned even if future due has precedence and zero writes")
    void returnedFirst() throws Exception {
        jdbcTemplate.update("UPDATE loan SET returned_at=?,return_date=?,due_date=? WHERE id=?",
                mutableClock.millis(), TODAY.toString(), TODAY.plusDays(1).toString(), loan.getId());
        register(loan.getId(), "{\"method\":\"PHONE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_ALREADY_RETURNED"));
        emptyWrites();
    }
    @Test @DisplayName("AC-DUN-04: existing undunned loan returns empty history, not a listed case")
    void emptyHistory() throws Exception {
        mockMvc.perform(get(path(loan.getId())).with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.loanId").value(loan.getId()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").value(nullValue()))
                .andExpect(jsonPath("$.closeReason").value(nullValue()))
                .andExpect(jsonPath("$.records").isEmpty());
        mockMvc.perform(get("/api/dunning-cases").with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }
    @Test @DisplayName("AC-DUN-04: history ascending createdAt/id; case pages descending latest time/loanId count loans once")
    void sortingAndPagination() throws Exception {
        register(loan.getId(), "{\"method\":\"PHONE\"}").andExpect(status().isCreated());
        register(loan.getId(), "{\"method\":\"OTHER\",\"note\":\"第二次\"}").andExpect(status().isCreated());
        var copy = testDataFactory.copy(testDataFactory.book("9787302423287", "第二本", "作者", null), CopyStatus.AVAILABLE);
        var second = testDataFactory.activeLoan(copy, loan.getReader(), TODAY.minusDays(40), TODAY.minusDays(1), admin.getUserId());
        register(second.getId(), "{\"method\":\"IN_APP\"}").andExpect(status().isCreated());
        mockMvc.perform(get(path(loan.getId())).with(user(admin)))
                .andExpect(jsonPath("$.records[*].id").value(contains(1, 2)))
                .andExpect(jsonPath("$.records[1].note").value("第二次"));
        mockMvc.perform(get("/api/dunning-cases").param("size", "1").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].loanId").value(second.getId()))
                .andExpect(jsonPath("$.items[0].reader.cardNo").value("R000001"))
                .andExpect(jsonPath("$.items[0].book.title").value("第二本"));
        mutableClock.setInstant(mutableClock.instant().plusSeconds(1));
        register(loan.getId(), "{\"method\":\"IN_PERSON\"}").andExpect(status().isCreated());
        mockMvc.perform(get("/api/dunning-cases").param("size", "1").with(user(admin)))
                .andExpect(jsonPath("$.items[0].loanId").value(loan.getId()))
                .andExpect(jsonPath("$.items[0].recordCount").value(3))
                .andExpect(jsonPath("$.items[0].lastRecordedAt").value(mutableClock.instant().toString()))
                .andExpect(jsonPath("$.items[0].copyBarcode").value("C00000001"))
                .andExpect(jsonPath("$.items[0].dueDate").value("2026-11-07"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].closedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].closeReason").value(nullValue()));
        mockMvc.perform(get("/api/dunning-cases").param("page", "1").param("size", "1").with(user(admin)))
                .andExpect(jsonPath("$.items[0].loanId").value(second.getId()));
        mockMvc.perform(get("/api/dunning-cases").param("page", "2").param("size", "1").with(user(admin)))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalItems").value(2));
    }
    @ParameterizedTest @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc", "status=WRONG", "sort=note,asc"})
    @DisplayName("AC-DUN-04: invalid status pagination or sort rejected")
    void invalidQuery(String query) throws Exception {
        String[] parts = query.split("=");
        mockMvc.perform(get("/api/dunning-cases").param(parts[0], parts[1]).with(user(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    @Test @DisplayName("AC-DUN-02 AC-AUTH-03/04/06: reader denied, anonymous401 with valid CSRF, missing/wrong CSRF403")
    void securityMatrix() throws Exception {
        for (String url : new String[]{path(loan.getId()), "/api/dunning-cases"}) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
            mockMvc.perform(get(url).with(user(reader))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mockMvc.perform(post(path(loan.getId())).with(cookieCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"PHONE\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path(loan.getId())).with(user(reader)).with(cookieCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"PHONE\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (boolean authenticated : new boolean[]{true, false}) {
            var missing = post(path(loan.getId()));
            var wrong = post(path(loan.getId())).cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "real"))
                    .header("X-XSRF-TOKEN", "wrong");
            if (authenticated) { missing.with(user(admin)); wrong.with(user(admin)); }
            mockMvc.perform(missing).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            mockMvc.perform(wrong).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        emptyWrites();
    }
}
