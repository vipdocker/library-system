package com.example.library.reminder;

import java.time.Duration;
import java.sql.SQLException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ReminderSchedulerTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @ComponentScan(basePackages="com.example.library.reminder",useDefaultFilters=false,
        includeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern=".*ReminderScheduler"))
    static class Config {
        @Bean ReminderScanner reminderScanner() { return mock(ReminderScanner.class); }
        @Bean ReminderProperties reminderProperties() { return new ReminderProperties(); }
    }
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    private ReminderScheduler scheduler(org.springframework.context.ApplicationContext ctx) {
        assertThat(ctx.containsBean("reminderScheduler")).isTrue();
        return ctx.getBean(ReminderScheduler.class);
    }
    private void tick(ReminderScheduler scheduler) { scheduler.tick(); }
    private void ready(ReminderScheduler scheduler,org.springframework.context.ConfigurableApplicationContext ctx) {
        scheduler.onReady(new ApplicationReadyEvent(new SpringApplication(),new String[0],ctx,Duration.ZERO));
    }
    @Test @DisplayName("AC-REM-06: never scan before ready event after demo runners, startup catches up once")
    void readiness() {
        runner.run(ctx -> {
            var scheduler=scheduler(ctx); var scanner=ctx.getBean(ReminderScanner.class);
            tick(scheduler); verifyNoInteractions(scanner);
            ready(scheduler,ctx.getSourceApplicationContext()); verify(scanner).scan();
            tick(scheduler); verify(scanner,times(2)).scan();
        });
    }
    @Test @DisplayName("AC-REM-06: scan-on-startup false retains scheduled scanning")
    void startupDisabled() {
        runner.run(ctx -> {
            ctx.getBean(ReminderProperties.class).setScanOnStartup(false);
            var scheduler=scheduler(ctx);var scanner=ctx.getBean(ReminderScanner.class);
            ready(scheduler,ctx.getSourceApplicationContext());verifyNoInteractions(scanner);
            tick(scheduler);verify(scanner).scan();
        });
    }
    @Test @DisplayName("AC-REM-03: busy is visible, next scheduled scan retries; unrelated SQL errors propagate")
    void busyThenRetry(CapturedOutput output) {
        runner.run(ctx -> {
            var scheduler=scheduler(ctx);var scanner=ctx.getBean(ReminderScanner.class);
            when(scanner.scan()).thenThrow(new DataAccessResourceFailureException("busy",new SQLException("SQLITE_BUSY", "",5))).thenReturn(1);
            ready(scheduler,ctx.getSourceApplicationContext());
            assertThat(output).contains("reminder.scan.busy").contains("retry-next-scan");
            tick(scheduler);verify(scanner,times(2)).scan();
            when(scanner.scan()).thenThrow(new IllegalStateException("business failure"));
            assertThatThrownBy(() -> tick(scheduler)).isInstanceOf(IllegalStateException.class).hasMessage("business failure");
        });
    }
}
