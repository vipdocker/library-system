package com.example.library.stats.dto;

/**
 * StatsOverview（design-v0.1 第 8.3 节）。
 * activeLoans 与 overdueLoans 互斥：未还且 due_date >= today 为 ACTIVE；
 * 未还且 due_date < today 为 OVERDUE（第 6 节派生状态、第 9.1 节逾期口径，应还日当天不算逾期）。
 */
public record StatsOverview(long totalBooks, long totalCopies, long availableCopies, long borrowedCopies,
                            long unavailableCopies, long totalReaders, long activeReaders,
                            long activeLoans, long overdueLoans, long borrowsToday, long returnsToday) {
}