package com.example.library.dunning;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.concurrent.*;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.*;
import com.example.library.security.LibraryUserPrincipal;
import com.example.library.support.IntegrationTestBase;
import com.example.library.support.TestDatabase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
class DunningTransactionTest extends IntegrationTestBase {
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    private Loan loan;
    private LibraryUserPrincipal admin;
    private String path;

    @BeforeEach void fixture() {
        var a = testDataFactory.admin("admin", "Admin@12345");
        var r = testDataFactory.reader("reader", "Reader@123", "读者", ReaderStatus.ACTIVE);
        var b = testDataFactory.book("9787115428028", "书", "作者", null);
        loan = testDataFactory.activeLoan(testDataFactory.copy(b, CopyStatus.AVAILABLE), r.reader(),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 7), a.getId());
        admin = testDataFactory.principal("admin");
        path = "/api/loans/" + loan.getId() + "/dunning-records";
    }
    private RequestPostProcessor cookieCsrf() throws Exception {
        String token = authTestHelper.freshCsrfToken();
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
    private int register(RequestPostProcessor csrf) throws Exception {
        return mockMvc.perform(post(path).with(user(admin)).with(csrf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"PHONE\",\"note\":\"秘密\"}"))
                .andReturn().getResponse().getStatus();
    }
    private int returnLoan(RequestPostProcessor csrf) throws Exception {
        return mockMvc.perform(post("/api/loans/" + loan.getId() + "/return").with(user(admin)).with(csrf))
                .andReturn().getResponse().getStatus();
    }
    private void counts(long expected) {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dunning_record", Long.class)).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification", Long.class)).isEqualTo(expected);
    }
    private void closed() throws Exception {
        mockMvc.perform(get(path).with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeReason").value("RETURNED"));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    @Test @DisplayName("AC-DUN-01/02: real notification insert SQL failure rolls back flushed dunning record")
    void notificationInsertFailure() throws Exception {
        jdbcTemplate.execute("CREATE TRIGGER dunning_test_notice_fail BEFORE INSERT ON notification BEGIN SELECT RAISE(ABORT,'dunning test failure'); END");
        try {
            mockMvc.perform(post(path).with(user(admin)).with(cookieCsrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"method\":\"PHONE\"}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(content().string(not(containsString("dunning test failure"))))
                    .andExpect(content().string(not(containsString("stackTrace"))));
            counts(0);
        } finally { jdbcTemplate.execute("DROP TRIGGER dunning_test_notice_fail"); }
        assertThat(register(cookieCsrf())).isEqualTo(201);
        counts(1);
    }
    @Test @DisplayName("AC-DUN-01: failure linking notice rolls back both SQL inserts")
    void notificationLinkFailure() throws Exception {
        jdbcTemplate.execute("CREATE TRIGGER dunning_test_link_fail BEFORE UPDATE OF notification_id ON dunning_record WHEN NEW.notification_id IS NOT NULL BEGIN SELECT RAISE(ABORT,'dunning link failure'); END");
        try {
            assertThat(register(cookieCsrf())).isEqualTo(500);
            counts(0);
        } finally { jdbcTemplate.execute("DROP TRIGGER dunning_test_link_fail"); }
        assertThat(register(cookieCsrf())).isEqualTo(201);
        counts(1);
    }
    @Test @DisplayName("AC-DUN-01: outer transaction rollback undoes successful record and notice writes")
    void outerRollback() throws Exception {
        var csrf = cookieCsrf();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            try { assertThat(register(csrf)).isEqualTo(201); counts(1); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            tx.setRollbackOnly();
        });
        counts(0);
    }
    @Test @DisplayName("AC-DUN-03: independent SQLite return writer locks first, registration waits and rejects returned loan")
    void returnWins() throws Exception {
        var csrf = cookieCsrf();
        var pool = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        try (var c = DriverManager.getConnection(TestDatabase.URL); var s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            s.executeUpdate("UPDATE loan SET returned_at=" + mutableClock.millis() + ",return_date='2026-11-17',return_operator_id=1 WHERE id=" + loan.getId());
            var f = pool.submit(() -> { entered.countDown(); return register(csrf); });
            await(entered);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            s.execute("COMMIT");
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            counts(0);
            closed();
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test @DisplayName("AC-DUN-03: registration commits first while real return competes, busy may retry without drift")
    void dunningWins() throws Exception {
        var registerCsrf = cookieCsrf();
        var returnCsrf = cookieCsrf();
        var pool = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var future = new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                try {
                    assertThat(register(registerCsrf)).isEqualTo(201);
                    counts(1);
                    future.set(pool.submit(() -> { entered.countDown(); return returnLoan(returnCsrf); }));
                    await(entered);
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            int returned = future.get().get(10, TimeUnit.SECONDS);
            assertThat(returned).isIn(200, 503);
            if (returned == 503) assertThat(returnLoan(cookieCsrf())).isEqualTo(200);
            closed(); counts(1);
            assertThat(register(cookieCsrf())).isEqualTo(409); counts(1);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
    @RepeatedTest(3) @DisplayName("AC-DUN-03 AC-LOAN-03: simultaneous registration/real return use distinct connections and serialized outcomes")
    void simultaneous() throws Exception {
        var registerCsrf = cookieCsrf();
        var returnCsrf = cookieCsrf();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var connections = ConcurrentHashMap.<Connection>newKeySet();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> dunning = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                connections.add(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource));
                ready.countDown(); await(go);
                try {
                    int status = register(registerCsrf);
                    if (status >= 400) tx.setRollbackOnly();
                    return status;
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }));
            Future<Integer> returned = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                connections.add(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource));
                ready.countDown(); await(go);
                try {
                    int status = returnLoan(returnCsrf);
                    if (status >= 400) tx.setRollbackOnly();
                    return status;
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }));
            await(ready); assertThat(connections).hasSize(2); go.countDown();
            int d = dunning.get(15, TimeUnit.SECONDS);
            int r = returned.get(15, TimeUnit.SECONDS);
            assertThat(d).isIn(201, 409, 503);
            assertThat(r).isIn(200, 503);
            System.out.printf("dunning.concurrent distinctConnections=%d registerStatus=%d returnStatus=%d%n",
                    connections.size(), d, r);
            if (r == 503) assertThat(returnLoan(cookieCsrf())).isEqualTo(200);
            closed(); counts(d == 201 ? 1 : 0);
            assertThat(register(cookieCsrf())).isEqualTo(409);
            counts(d == 201 ? 1 : 0);
        } finally { go.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test @DisplayName("AC-DUN-02 DOD-08: external write lock yields sanitized DB_BUSY503, no residual writes; explicit retry succeeds")
    void busyRecovery() throws Exception {
        var csrf = cookieCsrf();
        try (var c = DriverManager.getConnection(TestDatabase.URL); var s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            mockMvc.perform(post(path).with(user(admin)).with(csrf).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"method\":\"PHONE\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DB_BUSY"));
            counts(0); s.execute("ROLLBACK");
        }
        assertThat(register(cookieCsrf())).isEqualTo(201); counts(1);
    }
}
