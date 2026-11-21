package com.example.library.reminder;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

class ReminderMigrationTest {
    @TempDir Path dir;

    @Test @DisplayName("DOD-R1: empty database installs V2 and foreign keys")
    void emptyDatabase() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("empty.db") + "?foreign_keys=true";
        Flyway flyway = Flyway.configure().dataSource(url, "", "").load();
        flyway.migrate();
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            var rs = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('notification','dunning_record')");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("2");
            assertThat(s.executeQuery("PRAGMA foreign_key_check").next()).isFalse();
        }
    }

    @Test @DisplayName("DOD-R1: migrate disposable V1 with old borrowing data without losing rows")
    void oldDatabase() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("old.db") + "?foreign_keys=true";
        Flyway.configure().dataSource(url, "", "").target("1").load().migrate();
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO app_user VALUES(1,'admin','test','ADMIN',1,0,0)");
            s.executeUpdate("INSERT INTO app_user VALUES(2,'reader','test','READER',1,0,0)");
            s.executeUpdate("INSERT INTO reader VALUES(1,2,'R000001','reader',NULL,NULL,'ACTIVE',0,0)");
            s.executeUpdate("INSERT INTO book VALUES(1,'9787115428028','old book','author',NULL,NULL,NULL,NULL,0,0)");
            s.executeUpdate("INSERT INTO book_copy VALUES(1,1,'C00000001','BORROWED',0,0)");
            s.executeUpdate("INSERT INTO loan VALUES(1,1,1,0,'2026-01-01','2026-01-31',NULL,NULL,0,1,NULL,NULL)");
        }
        var flyway = Flyway.configure().dataSource(url, "", "").load();
        flyway.migrate();
        flyway.validate();
        try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
            var rs = s.executeQuery("SELECT due_date FROM loan WHERE id=1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("2026-01-31");
            rs = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('notification','dunning_record')");
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(s.executeQuery("PRAGMA foreign_key_check").next()).isFalse();
        }
    }
}
