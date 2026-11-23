package com.example.library.catalog;

import java.nio.file.Files;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BookCoverMigrationTest {
    @Test
    void acOl03ApplicationRestartReadsSameIsolatedDatabaseWithoutRemoteLookup() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("ol-restart-").resolve("library.db")
                + "?foreign_keys=true&busy_timeout=5000&journal_mode=WAL";
        byte[] bytes = BookCoverApiTest.image("jpeg", 9, 12);
        long id;
        String[] args = {"--spring.profiles.active=test", "--spring.datasource.url=" + url, "--server.port=0"};
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.example.library.LibraryApplication.class).run(args)) {
            var result = context.getBean(BookWriteService.class).create(new com.example.library.catalog.dto.BookCreateRequest(
                    "080442957X", "重启书", "作者", null, null, null, null, 2,
                    new com.example.library.catalog.dto.CoverChangeRequest("REPLACE", "image/jpeg",
                            java.util.Base64.getEncoder().encodeToString(bytes), null, null), "080442957X", null));
            id = result.id();
            assertThat(result.totalCopies()).isEqualTo(2);
        }
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.example.library.LibraryApplication.class).run(args)) {
            assertThat(context.getBean(BookCoverService.class).read(id).bytes()).isEqualTo(bytes);
            var detail = context.getBean(BookService.class).detail(id);
            assertThat(detail.openLibraryIsbn()).isEqualTo("080442957X");
            assertThat(detail.cover().width()).isEqualTo(9);
            assertThat(detail.totalCopies()).isEqualTo(2);
        }
    }

    @Test
    void dod01UpgradesPopulatedV2AndPersistsBlobAcrossReopen() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("ol-v2-upgrade-").resolve("library.db")
                + "?foreign_keys=true";
        var v2 = Flyway.configure().dataSource(url, null, null).target("2").load();
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(2);
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO app_user(id,username,password_hash,role,enabled,created_at,updated_at) VALUES(1,'r','fixture','READER',1,0,0),(2,'a','fixture','ADMIN',1,0,0)");
            sql.execute("INSERT INTO reader(id,user_id,card_no,name,status,created_at,updated_at) VALUES(1,1,'R000001','旧读者','ACTIVE',0,0)");
            sql.execute("INSERT INTO book(id,isbn,title,author,created_at,updated_at) VALUES(1,'080442957X','旧书','旧作者',0,0),(2,'9787115428028','无历史','作者',0,0)");
            sql.execute("INSERT INTO book_copy(id,book_id,barcode,status,created_at,updated_at) VALUES(1,1,'C00000001','BORROWED',0,0)");
            sql.execute("INSERT INTO loan(id,copy_id,reader_id,borrowed_at,borrow_date,due_date,renew_count,borrow_operator_id) VALUES(1,1,1,0,'2026-11-01','2026-12-01',0,2)");
        }
        var latest = Flyway.configure().dataSource(url, null, null).target("3").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        byte[] bytes = BookCoverApiTest.image("png", 4, 6);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT title,open_library_isbn FROM book WHERE id=1")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("旧书"); assertThat(result.getString(2)).isNull();
            }
            try (var insert = connection.prepareStatement("INSERT INTO book_cover VALUES(?,?,?,?,?,?,?,?)")) {
                for (int id : new int[]{1, 2}) {
                    insert.setInt(1, id); insert.setBytes(2, bytes); insert.setString(3, "image/png");
                    insert.setInt(4, 4); insert.setInt(5, 6); insert.setString(6, sha); insert.setString(7, "080442957X"); insert.setLong(8, 0);
                    insert.executeUpdate();
                }
            }
            assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM book WHERE id=1")).isInstanceOf(java.sql.SQLException.class);
            sql.executeUpdate("DELETE FROM book WHERE id=2");
            for (String update : new String[]{"width=4097", "height=0", "width=4096,height=4096", "sha256='BAD'", "media_type='image/gif'", "image_data=X''"}) {
                assertThatThrownBy(() -> sql.executeUpdate("UPDATE book_cover SET " + update + " WHERE book_id=1"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
        // Re-open the same isolated database; no external network client is involved in this read.
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT image_data,sha256 FROM book_cover WHERE book_id=1")) {
                assertThat(result.next()).isTrue(); assertThat(result.getBytes(1)).isEqualTo(bytes); assertThat(result.getString(2)).isEqualTo(sha);
            }
            try (var result = sql.executeQuery("SELECT count(*) FROM loan")) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(1); }
            try (var result = sql.executeQuery("SELECT count(*) FROM book_cover")) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(1); }
        }
    }
}
