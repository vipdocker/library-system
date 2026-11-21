package com.example.library.reminder;

import com.example.library.support.IntegrationTestBase;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReminderApiTest extends IntegrationTestBase {
    @Autowired ApplicationContext context;
    @Autowired ReminderScanner scanner;
    private static final LocalDate D = LocalDate.of(2026,11,20);
    private Loan loan;
    @BeforeEach void fixture() {
        var admin = testDataFactory.admin("admin", "Admin@12345");
        var r = testDataFactory.reader("reader", "Reader@123", "读者", ReaderStatus.ACTIVE);
        testDataFactory.reader("other", "Reader@123", "其他", ReaderStatus.ACTIVE);
        var book = testDataFactory.book("9787115428028", "测试书名", "作者", null);
        loan = testDataFactory.activeLoan(testDataFactory.copy(book, CopyStatus.AVAILABLE),r.reader(),D.minusDays(30),D,admin.getId());
    }
    private void scan() throws Exception {
        assertThat(context.containsBean("reminderScanner")).as("approved automatic scanner bean").isTrue();
        scanner.scan();
    }
    private long count() { return jdbcTemplate.queryForObject("SELECT count(*) FROM notification",Long.class); }
    // Use production CookieCsrfTokenRepository, not csrf(), which replaces the cached filter's repository.
    private org.springframework.test.web.servlet.request.RequestPostProcessor cookieCsrf() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    @ParameterizedTest @ValueSource(ints={3,2,1,0})
    @DisplayName("AC-REM-01/03: D-3 through D catches up exactly once")
    void dueWindow(int days) throws Exception {
        mutableClock.setToday(D.minusDays(days)); scan(); scan();
        assertThat(count()).isEqualTo(1);
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("reader"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("DUE_SOON"))
            .andExpect(jsonPath("$.items[0].content").value(org.hamcrest.Matchers.containsString("测试书名")))
            .andExpect(jsonPath("$.items[0].copyBarcode").value("C00000001"))
            .andExpect(jsonPath("$.items[0].dueDate").value(D.toString()))
            .andExpect(jsonPath("$.items[0].seq").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.items[0].overdueDays").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test @DisplayName("AC-REM-01/02: D-4 no notice, D+1 no old due reminder, Shanghai midnight overdue")
    void dateBoundary() throws Exception {
        mutableClock.setToday(D.minusDays(4)); scan(); assertThat(count()).isZero();
        mutableClock.setInstant(java.time.Instant.parse("2026-11-20T15:59:59Z")); scan();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification WHERE type='OVERDUE'", Long.class)).isZero();
        mutableClock.setInstant(java.time.Instant.parse("2026-11-20T16:00:00Z")); scan();
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM notification WHERE type='OVERDUE'", Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={1,7,8,14,15,19,100})
    @DisplayName("AC-REM-02/03: recovery only emits current fixed overdue cycle")
    void overdueCycle(int days) throws Exception {
        mutableClock.setToday(D.plusDays(days)); scan(); scan();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM notification",Long.class)).isEqualTo((days-1)/7+1);
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("reader"))))
            .andExpect(jsonPath("$.items[0].overdueDays").value(days));
    }
    @Test @DisplayName("AC-REM-02: continuous scanning enters cycles 1,2,3 without accumulating duplicates")
    void continuousCycles() throws Exception {
        for(int i=1;i<=20;i++) { mutableClock.setToday(D.plusDays(i)); scan(); }
        assertThat(count()).isEqualTo(3);
    }
    @Test @DisplayName("AC-REM-04/05: renewal retains unread snapshot and return prevents new notifications")
    void renewedAndReturned() throws Exception {
        scan();
        jdbcTemplate.update("UPDATE loan SET due_date=? WHERE id=?",D.plusDays(30).toString(),loan.getId());
        scan(); assertThat(count()).isEqualTo(1);
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("reader"))))
            .andExpect(jsonPath("$.items[0].dueDate").value(D.toString()))
            .andExpect(jsonPath("$.items[0].loanState.currentDueDate").value(D.plusDays(30).toString()))
            .andExpect(jsonPath("$.items[0].readAt").value(org.hamcrest.Matchers.nullValue()));
        mutableClock.setToday(D.plusDays(27)); scan(); assertThat(count()).isEqualTo(2);
        jdbcTemplate.update("UPDATE loan SET returned_at=?,return_date=? WHERE id=?",mutableClock.millis(),D.plusDays(27).toString(),loan.getId());
        mutableClock.setToday(D.plusDays(31)); scan(); assertThat(count()).isEqualTo(2);
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("reader"))))
            .andExpect(jsonPath("$.items[0].loanState.status").value("RETURNED"));
    }
    @Test @DisplayName("AC-REM-05: return committed before scanner inserts zero")
    void returnedFirst() throws Exception {
        jdbcTemplate.update("UPDATE loan SET returned_at=?,return_date=? WHERE id=?",mutableClock.millis(),D.toString(),loan.getId());
        scan(); assertThat(count()).isZero();
    }
    @Test @DisplayName("AC-REM-06: test profile has no automatic scheduling")
    void testProfile() {
        assertThat(context.containsBean("reminderScheduler")).isFalse();
    }
    @Test @DisplayName("AC-REM-01: disabled reader still receives persistent notices")
    void disabledReader() throws Exception {
        jdbcTemplate.update("UPDATE reader SET status='DISABLED'"); scan(); assertThat(count()).isEqualTo(1);
    }
    @Test @DisplayName("AC-NTF-01/02: inbox isolation, unread counts, read idempotence and read-all")
    void inbox() throws Exception {
        scan(); mutableClock.setToday(D.plusDays(1)); scan();
        var reader = testDataFactory.principal("reader");
        mockMvc.perform(get("/api/notifications/me").param("readerId","2").param("size","1").with(user(reader)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items[0].id").value(2));
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("other"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/notifications/me/unread-count").with(user(reader))).andExpect(jsonPath("$.unreadCount").value(2));
        for(int i=0;i<2;i++) mockMvc.perform(post("/api/notifications/me/1/read").with(user(reader)).with(cookieCsrf()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/notifications/me").param("unreadOnly","true").with(user(reader)))
            .andExpect(jsonPath("$.totalItems").value(1));
        mockMvc.perform(post("/api/notifications/me/read-all").with(user(reader)).with(cookieCsrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        mockMvc.perform(post("/api/notifications/me/read-all").with(user(reader)).with(cookieCsrf())).andExpect(jsonPath("$.updated").value(0));
        mockMvc.perform(get("/api/notifications/me/unread-count").with(user(reader))).andExpect(jsonPath("$.unreadCount").value(0));
    }
    @Test @DisplayName("AC-NTF-02: foreign/missing ID 404 and CSRF prevents any changes")
    void deniedWrites() throws Exception {
        scan();
        for(long id:new long[]{1,999}) mockMvc.perform(post("/api/notifications/me/"+id+"/read").with(user(testDataFactory.principal("other"))).with(cookieCsrf()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        for(String path:new String[]{"/api/notifications/me/1/read","/api/notifications/me/read-all"}) {
            mockMvc.perform(post(path).with(user(testDataFactory.principal("reader")))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            mockMvc.perform(post(path).with(user(testDataFactory.principal("reader"))).header("X-XSRF-TOKEN","wrong")).andExpect(status().isForbidden());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification WHERE read_at IS NOT NULL",Long.class)).isZero();
    }
    @Test @DisplayName("AC-NTF-02: all notification endpoints anonymous401/admin403")
    void roleMatrix() throws Exception {
        for(String path:new String[]{"/api/notifications/me","/api/notifications/me/unread-count"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).with(user(testDataFactory.principal("admin")))).andExpect(status().isForbidden());
        }
        for(String path:new String[]{"/api/notifications/me/1/read","/api/notifications/me/read-all"}) {
            mockMvc.perform(post(path).with(cookieCsrf())).andExpect(status().isUnauthorized());
            mockMvc.perform(post(path).with(user(testDataFactory.principal("admin"))).with(cookieCsrf())).andExpect(status().isForbidden());
        }
    }
    @Test @DisplayName("DOD-R2 AC-NTF-02: successful notification POST preserves real cookie CSRF repository")
    void cookieCsrfStillWorksAfterNotificationWrite() throws Exception {
        mockMvc.perform(post("/api/notifications/me/read-all").with(user(testDataFactory.principal("reader"))).with(cookieCsrf()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isNoContent())
            .andExpect(cookie().exists("XSRF-TOKEN"));
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","size=0","size=101","unreadOnly=wrong"})
    @DisplayName("AC-NTF-01: invalid pagination or boolean is rejected")
    void invalidParams(String param) throws Exception {
        var parts=param.split("=");
        mockMvc.perform(get("/api/notifications/me").param(parts[0],parts[1]).with(user(testDataFactory.principal("reader"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
