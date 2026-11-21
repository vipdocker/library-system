package com.example.library.reminder;

import com.example.library.LibraryApplication;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class ReminderStartupTest {
    @TempDir Path dir;
    private ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(LibraryApplication.class).run(
            "--spring.profiles.active=dev", "--server.port=0",
            "--spring.datasource.url=jdbc:sqlite:"+database+"?foreign_keys=true&busy_timeout=5000&journal_mode=WAL",
            "--lib.reminder.enabled=true", "--lib.reminder.scan-on-startup=true",
            "--lib.reminder.scan-interval=PT1H", "--logging.level.root=WARN",
            "--logging.level.com.example.library=INFO");
    }
    private long count(Path database,String table) throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement();var rs=s.executeQuery("SELECT count(*) FROM "+table)) {
            assertThat(rs.next()).isTrue();return rs.getLong(1);
        }
    }
    @Test @DisplayName("AC-REM-03/06 DOD-R1: real random-port dev startup after seed, shutdown and restart catch-up are idempotent")
    void startupAndRestart() throws Exception {
        Path db=dir.resolve("startup.db");
        long loans;
        try(var context=start(db)) {
            assertThat(context.containsBean("reminderScheduler")).isTrue();
            loans=count(db,"loan");assertThat(loans).isPositive();
            assertThat(count(db,"notification")).isEqualTo(1);
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:"+((WebServerApplicationContext)context).getWebServer().getPort()+"/api/auth/csrf"))
                    .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(204);
        }
        try(var context=start(db)) {
            assertThat(count(db,"loan")).isEqualTo(loans);
            assertThat(count(db,"notification")).isEqualTo(1);
            assertThat(context.getBean(ReminderScanner.class).scan()).isZero();
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var rs=s.executeQuery("SELECT type,seq FROM notification")) {
                assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("OVERDUE");assertThat(rs.getInt(2)).isEqualTo(2);
            }
        }
    }
}
