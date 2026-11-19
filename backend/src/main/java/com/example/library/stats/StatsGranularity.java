package com.example.library.stats;

/**
 * 借阅序列粒度（design-v0.1 第 8.4 节 GET /api/stats/loans，granularity=DAY|MONTH，默认 DAY）。
 * 非法值由 StatsService 校验为 400 VALIDATION_FAILED。
 */
public enum StatsGranularity {
    DAY,
    MONTH
}