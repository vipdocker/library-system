package com.example.library.catalog;

import java.nio.file.Files;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** V5 only: empty-schema install and populated V4 upgrade must both preserve existing data (DOD-S2). */
class ShowApiMigrationTest {

    @Test
    void dodS2EmptyDatabaseInstallsV5WithDefaultsAndConstraints() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("showapi-v5-empty-").resolve("library.db") + "?foreign_keys=true";
        assertThat(Flyway.configure().dataSource(url, null, null).load().migrate().migrationsExecuted).isEqualTo(5);
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rs = sql.executeQuery("SELECT enabled,app_key,revision,updated_at,updated_by FROM showapi_settings WHERE id=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero(); assertThat(rs.getString(2)).isNull();
                assertThat(rs.getLong(3)).isZero(); assertThat(rs.getObject(4)).isNull(); assertThat(rs.getString(5)).isNull();
            }
            for (String change : new String[]{"enabled=1", "revision=-1", "app_key=''", "app_key='   '"}) {
                assertThatThrownBy(() -> sql.execute("UPDATE showapi_settings SET " + change))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            assertThatThrownBy(() -> sql.execute("INSERT INTO showapi_settings(id,enabled,revision) VALUES(2,0,0)"))
                    .isInstanceOf(java.sql.SQLException.class);
            sql.execute("INSERT INTO book(id,isbn,title,author,created_at,updated_at) VALUES(1,'080442957X','书','作者',0,0)");
            assertThatThrownBy(() -> sql.execute("UPDATE book SET source_provider='BAD' WHERE id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> sql.execute("UPDATE book SET source_provider='SHOWAPI' WHERE id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> sql.execute("UPDATE book SET source_isbn='080442957X' WHERE id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
            sql.execute("UPDATE book SET open_library_isbn='080442957X' WHERE id=1");
            assertThatThrownBy(() -> sql.execute("UPDATE book SET source_provider='SHOWAPI',source_isbn='080442957X' WHERE id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
            sql.execute("UPDATE book SET source_provider='SHOWAPI',source_isbn='080442957X',open_library_isbn=NULL WHERE id=1");
            byte[] image = new byte[]{1, 2, 3};
            try (var insert = connection.prepareStatement(
                    "INSERT INTO book_cover(book_id,image_data,media_type,width,height,sha256,source_isbn,updated_at) VALUES(1,?,'image/png',1,1,?,NULL,0)")) {
                insert.setBytes(1, image); insert.setString(2, "a".repeat(64)); insert.executeUpdate();
            }
            assertThatThrownBy(() -> sql.execute("UPDATE book_cover SET provider='SHOWAPI' WHERE book_id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> sql.execute("UPDATE book_cover SET provider='BAD',source_isbn='080442957X' WHERE book_id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
            sql.execute("UPDATE book_cover SET provider='SHOWAPI',source_isbn='080442957X' WHERE book_id=1");
        }
    }

    @Test
    void dodS2PopulatedV4UpgradePreservesBookCoverLoanProxyAndBackfillsSources() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("showapi-v4-upgrade-").resolve("library.db") + "?foreign_keys=true";
        assertThat(Flyway.configure().dataSource(url, null, null).target("4").load().migrate().migrationsExecuted).isEqualTo(4);
        byte[] image = new byte[]{9, 8, 7, 6};
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO app_user VALUES(1,'reader','fixture','READER',1,0,0),(2,'admin','fixture','ADMIN',1,0,0)");
            sql.execute("INSERT INTO reader(id,user_id,card_no,name,status,created_at,updated_at) VALUES(1,1,'R000001','读者','ACTIVE',0,0)");
            sql.execute("INSERT INTO book(id,isbn,title,author,created_at,updated_at,open_library_isbn) VALUES(1,'080442957X','旧书','作者',0,0,'080442957X')");
            sql.execute("INSERT INTO book(id,isbn,title,author,created_at,updated_at) VALUES(2,'9787115428028','无来源','作者',0,0)");
            sql.execute("INSERT INTO book_copy VALUES(1,1,'C00000001','BORROWED',0,0)");
            sql.execute("INSERT INTO loan(id,copy_id,reader_id,borrowed_at,borrow_date,due_date,renew_count,borrow_operator_id) VALUES(1,1,1,0,'2026-11-01','2026-12-01',0,2)");
            try (var insert = connection.prepareStatement("INSERT INTO book_cover VALUES(1,?,'image/png',1,1,?,'080442957X',0)")) {
                insert.setBytes(1, image); insert.setString(2, "b".repeat(64)); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO book_cover VALUES(2,?,'image/png',1,1,?,NULL,0)")) {
                insert.setBytes(1, image); insert.setString(2, "c".repeat(64)); insert.executeUpdate();
            }
            sql.execute("UPDATE system_proxy_settings SET enabled=1,host='127.0.0.1',port=1080,revision=3,updated_at=5,updated_by='admin' WHERE id=1");
        }
        var latest = Flyway.configure().dataSource(url, null, null).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rs = sql.executeQuery("SELECT title,open_library_isbn,source_provider,source_isbn FROM book WHERE id=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("旧书"); assertThat(rs.getString(2)).isEqualTo("080442957X");
                assertThat(rs.getString(3)).isEqualTo("OPEN_LIBRARY"); assertThat(rs.getString(4)).isEqualTo("080442957X");
            }
            try (var rs = sql.executeQuery("SELECT source_provider,source_isbn FROM book WHERE id=2")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isNull(); assertThat(rs.getString(2)).isNull();
            }
            try (var rs = sql.executeQuery("SELECT image_data,provider,source_isbn FROM book_cover WHERE book_id=1")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getBytes(1)).isEqualTo(image);
                assertThat(rs.getString(2)).isEqualTo("OPEN_LIBRARY"); assertThat(rs.getString(3)).isEqualTo("080442957X");
            }
            try (var rs = sql.executeQuery("SELECT provider FROM book_cover WHERE book_id=2")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isNull();
            }
            try (var rs = sql.executeQuery("SELECT count(*) FROM loan WHERE copy_id=1 AND returned_at IS NULL")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (var rs = sql.executeQuery("SELECT enabled,host,port,revision FROM system_proxy_settings WHERE id=1")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.getString(2)).isEqualTo("127.0.0.1"); assertThat(rs.getInt(3)).isEqualTo(1080); assertThat(rs.getLong(4)).isEqualTo(3);
            }
        }
    }

    @Test
    void acS4ApplicationRestartRestoresOnlyCommittedShowApiSettings() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("showapi-restart-").resolve("library.db")
                + "?foreign_keys=true&busy_timeout=5000&journal_mode=WAL";
        String[] args = {"--spring.profiles.active=test", "--spring.datasource.url=" + url, "--server.port=0"};
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        com.example.library.catalog.settings.ShowApiSettings expected;
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.example.library.LibraryApplication.class).run(args)) {
            var service = context.getBean(com.example.library.catalog.settings.ShowApiSettingsService.class);
            assertThat(service.snapshot().revision()).isZero();
            assertThat(service.snapshot().enabled()).isFalse();
            assertThat(service.snapshot().appKey()).isNull();
            expected = service.replace(mapper.readTree(
                    "{\"enabled\":true,\"appKeyAction\":\"REPLACE\",\"appKey\":\"FAKE-TEST-KEY-000000\",\"expectedRevision\":0}"), "admin");
            assertThat(expected.revision()).isEqualTo(1);
            // A conflicting replacement must not change the committed snapshot.
            assertThatThrownBy(() -> service.replace(mapper.readTree(
                    "{\"enabled\":false,\"appKeyAction\":\"CLEAR\",\"expectedRevision\":0}"), "admin"))
                    .isInstanceOf(com.example.library.common.error.ApiException.class);
            assertThat(service.snapshot()).isEqualTo(expected);
        }
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.example.library.LibraryApplication.class).run(args)) {
            var restored = context.getBean(com.example.library.catalog.settings.ShowApiSettingsProvider.class).snapshot();
            assertThat(restored.enabled()).isTrue();
            assertThat(restored.appKey()).isEqualTo("FAKE-TEST-KEY-000000");
            assertThat(restored.revision()).isEqualTo(expected.revision());
            assertThat(restored.updatedAt()).isEqualTo(expected.updatedAt());
            assertThat(restored.toString()).doesNotContain("FAKE-TEST-KEY");
        }
    }
}