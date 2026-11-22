package com.example.library.common.proxy;

import java.nio.file.Files;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProxySettingsMigrationTest {
    @Test
    void acProxy02PopulatedV3UpgradePreservesBinaryBookAndLoanAndSingletonConstraints() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("proxy-v3-upgrade-").resolve("library.db") + "?foreign_keys=true";
        assertThat(Flyway.configure().dataSource(url, null, null).target("3").load().migrate().migrationsExecuted).isEqualTo(3);
        byte[] image = new byte[]{1, 2, 3, 4};
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO app_user VALUES(1,'reader','fixture','READER',1,0,0),(2,'admin','fixture','ADMIN',1,0,0)");
            sql.execute("INSERT INTO reader(id,user_id,card_no,name,status,created_at,updated_at) VALUES(1,1,'R000001','读者','ACTIVE',0,0)");
            sql.execute("INSERT INTO book(id,isbn,title,author,created_at,updated_at,open_library_isbn) VALUES(1,'080442957X','旧书','作者',0,0,'080442957X')");
            sql.execute("INSERT INTO book_copy VALUES(1,1,'C00000001','BORROWED',0,0)");
            sql.execute("INSERT INTO loan(id,copy_id,reader_id,borrowed_at,borrow_date,due_date,renew_count,borrow_operator_id) VALUES(1,1,1,0,'2026-11-01','2026-12-01',0,2)");
            try (var insert = connection.prepareStatement("INSERT INTO book_cover VALUES(1,?,'image/png',1,1,?,'080442957X',0)")) {
                insert.setBytes(1, image); insert.setString(2, "a".repeat(64)); insert.executeUpdate();
            }
        }
        var latest = Flyway.configure().dataSource(url, null, null).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rs = sql.executeQuery("SELECT image_data FROM book_cover")) { assertThat(rs.next()).isTrue(); assertThat(rs.getBytes(1)).isEqualTo(image); }
            try (var rs = sql.executeQuery("SELECT title,open_library_isbn FROM book")) { assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo("旧书"); assertThat(rs.getString(2)).isEqualTo("080442957X"); }
            try (var rs = sql.executeQuery("SELECT count(*) FROM loan WHERE copy_id=1 AND returned_at IS NULL")) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isEqualTo(1); }
            for (String change : new String[]{"enabled=1", "host='localhost'", "port=9999", "revision=-1", "id=2"}) {
                assertThatThrownBy(() -> sql.execute("UPDATE system_proxy_settings SET " + change)).isInstanceOf(java.sql.SQLException.class);
            }
            assertThatThrownBy(() -> sql.execute("INSERT INTO system_proxy_settings(id,enabled,revision) VALUES(2,0,0)")).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void acProxy02ApplicationRestartRestoresOnlyCommittedSettings() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("proxy-restart-").resolve("library.db") + "?foreign_keys=true";
        String[] args = {"--spring.profiles.active=test", "--spring.datasource.url=" + url, "--server.port=0"};
        ProxySnapshot expected;
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.example.library.LibraryApplication.class).run(args)) {
            var service = context.getBean(ProxySettingsService.class);
            assertThat(service.snapshot().revision()).isZero();
            expected = service.replace(tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"enabled\":true,\"host\":\"::1\",\"port\":9999}"), "admin");
        }
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.example.library.LibraryApplication.class).run(args)) {
            assertThat(context.getBean(ProxySnapshotProvider.class).snapshot()).isEqualTo(expected);
        }
    }
}
