package com.example.library.stats.dto;

/**
 * TopBook（design-v0.1 第 8.3 节）。borrowCount = 区间内 borrow_date 落在区间的借阅条数
 * （包括之后已归还的；续借不计入，第 8.4 节统计口径）。
 */
public record TopBook(long bookId, String title, String author, String isbn, long borrowCount) {
}