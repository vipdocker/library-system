package com.example.library.stats.dto;

/**
 * OverdueItem（design-v0.1 第 8.3 节）。只包含未还且 due_date < today 的借阅；
 * overdueDays = today - due_date（today 按 lib.timezone，注入 Clock 取得）。
 */
public record OverdueItem(long loanId, ReaderRef reader, BookRef book, String copyBarcode,
                          String borrowDate, String dueDate, long overdueDays) {

    public record ReaderRef(long id, String cardNo, String name, String phone) {
    }

    public record BookRef(long id, String title, String isbn) {
    }
}