package com.example.library.loan.dto;

/**
 * Loan（design-v0.1 第 8.3 节，与前端 api/types.ts 的 Loan 一致）：
 * { id, reader{id,cardNo,name}, copy{id,barcode}, book{id,title,author,isbn},
 *   borrowDate, dueDate, returnDate|null, renewCount, maxRenewals, status, overdueDays, renewable }。
 * 日期为 YYYY-MM-DD 字符串；空值输出 null，不省略字段。不含任何口令字段（AC-AUTH-08）。
 * renewable 表示“当前调用者在当前时间点能否续借”，只是 UI 提示，服务端仍完整校验。
 */
public record LoanResponse(Long id, ReaderRef reader, CopyRef copy, BookRef book,
                           String borrowDate, String dueDate, String returnDate,
                           int renewCount, int maxRenewals, String status, long overdueDays, boolean renewable) {

    public record ReaderRef(Long id, String cardNo, String name) {
    }

    public record CopyRef(Long id, String barcode) {
    }

    public record BookRef(Long id, String title, String author, String isbn) {
    }
}