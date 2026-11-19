package com.example.library.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 可拨动的 Clock（测试支撑，共享契约：T01 之后只读）。
 * 作为 @Primary Clock 注入（TestSupportConfig），逾期/统计测试可确定性拨动时间。
 * 用法：mutableClock.setInstant(...)、setToday(LocalDate)（当天 00:00，Asia/Shanghai）、advance(Duration)。
 */
public class MutableClock extends Clock {

    /** 默认固定时刻：2026-11-17T02:00:00Z = 上海 2026-11-17 10:00。 */
    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-11-17T02:00:00Z");
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Instant instant;
    private volatile ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock fixedDefault() {
        return new MutableClock(DEFAULT_INSTANT, DEFAULT_ZONE);
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    /** 设为指定业务日期的 00:00（当前时区）。 */
    public void setToday(LocalDate date) {
        this.instant = date.atStartOfDay(zone).toInstant();
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public void reset() {
        this.instant = DEFAULT_INSTANT;
        this.zone = DEFAULT_ZONE;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public long millis() {
        return instant.toEpochMilli();
    }
}