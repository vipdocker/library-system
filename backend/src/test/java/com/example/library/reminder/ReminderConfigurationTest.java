package com.example.library.reminder;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class ReminderConfigurationTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @ConfigurationPropertiesScan("com.example.library.reminder")
    static class Config {}
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Test @DisplayName("AC-REM-06: default independent reminder properties")
    void defaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanNamesForAnnotation(org.springframework.boot.context.properties.ConfigurationProperties.class)).hasSize(1);
            Object bean = ctx.getBean(ctx.getBeanNamesForAnnotation(org.springframework.boot.context.properties.ConfigurationProperties.class)[0]);
            assertThat(bean.getClass().getMethod("getDueSoonDays").invoke(bean)).isEqualTo(3);
            assertThat(bean.getClass().getMethod("getOverdueRepeatDays").invoke(bean)).isEqualTo(7);
            assertThat(bean.getClass().getMethod("getScanInterval").invoke(bean)).isEqualTo(java.time.Duration.ofHours(1));
            assertThat(bean.getClass().getMethod("isEnabled").invoke(bean)).isEqualTo(true);
            assertThat(bean.getClass().getMethod("isScanOnStartup").invoke(bean)).isEqualTo(true);
        });
    }
    @ParameterizedTest @ValueSource(strings={"due-soon-days=0","due-soon-days=-1","overdue-repeat-days=0","overdue-repeat-days=-7","scan-interval=PT0S","scan-interval=-PT1S","scan-interval=PT0.000001S","scan-interval=wrong"})
    @DisplayName("AC-REM-06: invalid settings fail context startup even when scheduling disabled")
    void invalid(String property) {
        runner.withPropertyValues("lib.reminder.enabled=false", "lib.reminder."+property)
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
