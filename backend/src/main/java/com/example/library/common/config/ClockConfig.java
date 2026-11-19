package com.example.library.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * K7：注入 Clock，时区可配置（默认 Asia/Shanghai）。非法时区在此抛异常导致启动失败。
 * 测试用 @Primary MutableClock 覆盖。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(LibraryProperties properties) {
        return Clock.system(ZoneId.of(properties.getTimezone()));
    }
}