package com.example.library.reminder;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.example.library.common.config.LibraryProperties;
import com.example.library.loan.LoanPolicy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ReminderConcurrencyTest {
    @TempDir Path dir;
    private String url;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ReminderWriter writer;
    private ReminderProperties properties;
    private final LocalDate today=LocalDate.of(2026,11,17);
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"),ZoneId.of("Asia/Shanghai"));
    @BeforeEach void fixture() {
        url="jdbc:sqlite:"+dir.resolve("concurrent.db")+"?foreign_keys=true&journal_mode=WAL&busy_timeout=200";
        ds=new DriverManagerDataSource(url);jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc.update("INSERT INTO app_user VALUES(1,'admin','test','ADMIN',1,0,0)");
        jdbc.update("INSERT INTO app_user VALUES(2,'reader','test','READER',1,0,0)");
        jdbc.update("INSERT INTO reader VALUES(1,2,'R000001','reader',NULL,NULL,'ACTIVE',0,0)");
        jdbc.update("INSERT INTO book(id,isbn,title,author,publisher,publish_year,category_id,description,created_at,updated_at) VALUES(1,'9787115428028','book','author',NULL,NULL,NULL,NULL,0,0)");
        jdbc.update("INSERT INTO book_copy VALUES(1,1,'C00000001','BORROWED',0,0)");
        jdbc.update("INSERT INTO loan VALUES(1,1,1,0,'2026-10-01','2026-11-20',NULL,NULL,0,1,NULL,NULL)");
        properties=new ReminderProperties();manager=new DataSourceTransactionManager(ds);
        writer=proxyWriter(jdbc,manager);
    }
    private ReminderWriter proxyWriter(JdbcTemplate template,DataSourceTransactionManager txManager) {
        var proxy=new ProxyFactory(new ReminderWriter(template,properties));
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(txManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (ReminderWriter)proxy.getProxy();
    }
    private ReminderScanner scanner(JdbcTemplate template,ReminderWriter actualWriter) {
        return new ReminderScanner(template,actualWriter,properties,new LoanPolicy(new LibraryProperties(),clock),clock);
    }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM notification",Long.class); }
    @RepeatedTest(3) @DisplayName("AC-REM-03: eight simultaneous independent connections enforce database deduplication")
    void independentConnections() throws Exception {
        var connections=ConcurrentHashMap.<Connection>newKeySet();
        var ready=new CountDownLatch(8);var go=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);
        try {
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                connections.add(DataSourceUtils.getConnection(ds)); ready.countDown();
                try { if(!go.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException(ex); }
                return writer.scanLoan(1L,today,clock.instant());
            })));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(connections).hasSize(8);go.countDown();
            int sum=0;for(var f:futures) sum+=f.get(10,TimeUnit.SECONDS);
            assertThat(sum).isEqualTo(1);assertThat(count()).isEqualTo(1);
            // A new datasource, writer and scanner simulate restart with no in-memory dedup cache.
            var freshDS=new DriverManagerDataSource(url);var freshJdbc=new JdbcTemplate(freshDS);
            assertThat(scanner(freshJdbc,proxyWriter(freshJdbc,new DataSourceTransactionManager(freshDS))).scan()).isZero();
            assertThat(count()).isEqualTo(1);
        } finally { go.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test @DisplayName("AC-REM-05: return locks first, scan waits and then inserts nothing")
    void returnWins() throws Exception {
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");s.executeUpdate("UPDATE loan SET returned_at=1,return_date='2026-11-17' WHERE id=1 AND returned_at IS NULL");
            var pool=Executors.newSingleThreadExecutor();
            try {
                var entered=new CountDownLatch(1);
                var future=pool.submit(() -> { entered.countDown();return writer.scanLoan(1L,today,clock.instant()); });
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();s.execute("COMMIT");
                assertThat(future.get(5,TimeUnit.SECONDS)).isZero();assertThat(count()).isZero();
            } finally { pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
        }
    }
    @Test @DisplayName("AC-REM-05: scan locks first, return waits; existing reminder retained and no later insert")
    void scanWins() throws Exception {
        var pool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);
        try {
            var tx=new TransactionTemplate(manager);
            tx.executeWithoutResult(state -> {
                assertThat(writer.scanLoan(1L,today,clock.instant())).isEqualTo(1);
                var future=pool.submit(() -> { entered.countDown(); return jdbc.update("UPDATE loan SET returned_at=1,return_date='2026-11-17' WHERE id=1 AND returned_at IS NULL"); });
                pending=future;
                try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException(ex); }
            });
            assertThat(pending.get(5,TimeUnit.SECONDS)).isEqualTo(1);assertThat(count()).isEqualTo(1);
            assertThat(writer.scanLoan(1L,today.plusDays(10),clock.instant())).isZero();
        } finally { pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }
    private Future<Integer> pending;
    @Test @DisplayName("AC-REM-03: actual external SQLite busy logs retry, then succeeds without blocking inbox reads")
    void busyRecovery(CapturedOutput output) throws Exception {
        var scanner=scanner(jdbc,writer);var scheduler=new ReminderScheduler(scanner,properties);
        try(var context=new GenericApplicationContext();var c=DriverManager.getConnection(url);var s=c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");s.executeUpdate("UPDATE loan SET renew_count=renew_count WHERE id=1");
            scheduler.onReady(new ApplicationReadyEvent(new SpringApplication(),new String[0],context,Duration.ZERO));
            assertThat(output).contains("reminder.scan.busy").contains("retry-next-scan");
            assertThat(count()).isZero(); // WAL read still works under a competing writer.
            s.execute("ROLLBACK");scheduler.tick();assertThat(count()).isEqualTo(1);
            scheduler.tick();assertThat(count()).isEqualTo(1);
        }
    }
    @Test @DisplayName("AC-REM-03: unexpected SQL failure is not treated as deduplication success")
    void unexpectedFailure() {
        jdbc.execute("DROP TABLE notification");
        assertThatThrownBy(() -> writer.scanLoan(1L,today,clock.instant())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
