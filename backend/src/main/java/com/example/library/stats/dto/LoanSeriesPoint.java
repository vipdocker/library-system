package com.example.library.stats.dto;

/**
 * LoanSeriesPoint（design-v0.1 第 8.3 节）。
 * period：DAY 粒度为 YYYY-MM-DD，MONTH 粒度为 YYYY-MM；区间内每个周期都有一项，无数据补 0。
 */
public record LoanSeriesPoint(String period, long borrowCount, long returnCount) {
}