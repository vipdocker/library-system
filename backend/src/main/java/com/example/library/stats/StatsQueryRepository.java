package com.example.library.stats;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.library.stats.dto.StatsOverview;
import com.example.library.stats.dto.TopBook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * stats 模块私有只读查询（design-v0.1 第 8.4 节；plan-v1.1 T07：不修改任何 domain Repository）。
 * - 全部 SQL 使用 JdbcClient 命名参数绑定，不拼接任何用户输入；
 * - 日期列为 TEXT YYYY-MM-DD（K4），字典序比较即日期先后比较，月粒度用 substr(borrow_date,1,7)；
 * - 逾期口径与 T05/T06 一致：returned_at IS NULL 且 due_date < today（应还日当天不算逾期）；
 * - 排序子句只能取自本类常量（排序字段已由 StatsService 白名单校验）。
 */
@Repository
public class StatsQueryRepository {

    private static final String SQL_OVERVIEW = """
            SELECT
              (SELECT COUNT(*) FROM book)                                          AS total_books,
              (SELECT COUNT(*) FROM book_copy)                                     AS total_copies,
              (SELECT COUNT(*) FROM book_copy WHERE status = 'AVAILABLE')          AS available_copies,
              (SELECT COUNT(*) FROM book_copy WHERE status = 'BORROWED')           AS borrowed_copies,
              (SELECT COUNT(*) FROM book_copy WHERE status = 'UNAVAILABLE')        AS unavailable_copies,
              (SELECT COUNT(*) FROM reader)                                        AS total_readers,
              (SELECT COUNT(*) FROM reader WHERE status = 'ACTIVE')                AS active_readers,
              (SELECT COUNT(*) FROM loan WHERE returned_at IS NULL AND due_date >= :today) AS active_loans,
              (SELECT COUNT(*) FROM loan WHERE returned_at IS NULL AND due_date < :today)  AS overdue_loans,
              (SELECT COUNT(*) FROM loan WHERE borrow_date = :today)               AS borrows_today,
              (SELECT COUNT(*) FROM loan WHERE return_date = :today)               AS returns_today
            """;

    private static final String SQL_BORROW_BY_DAY = """
            SELECT borrow_date AS period, COUNT(*) AS cnt
            FROM loan
            WHERE borrow_date BETWEEN :from AND :to
            GROUP BY borrow_date
            """;

    private static final String SQL_BORROW_BY_MONTH = """
            SELECT substr(borrow_date, 1, 7) AS period, COUNT(*) AS cnt
            FROM loan
            WHERE borrow_date BETWEEN :from AND :to
            GROUP BY substr(borrow_date, 1, 7)
            """;

    private static final String SQL_RETURN_BY_DAY = """
            SELECT return_date AS period, COUNT(*) AS cnt
            FROM loan
            WHERE return_date IS NOT NULL AND return_date BETWEEN :from AND :to
            GROUP BY return_date
            """;

    private static final String SQL_RETURN_BY_MONTH = """
            SELECT substr(return_date, 1, 7) AS period, COUNT(*) AS cnt
            FROM loan
            WHERE return_date IS NOT NULL AND return_date BETWEEN :from AND :to
            GROUP BY substr(return_date, 1, 7)
            """;

    private static final String SQL_TOP_BOOKS = """
            SELECT b.id AS book_id, b.title, b.author, b.isbn, COUNT(*) AS borrow_count
            FROM loan l
            JOIN book_copy c ON c.id = l.copy_id
            JOIN book b ON b.id = c.book_id
            WHERE l.borrow_date BETWEEN :from AND :to
            GROUP BY b.id, b.title, b.author, b.isbn
            ORDER BY borrow_count DESC, b.title ASC, b.id ASC
            LIMIT :limit
            """;

    private static final String SQL_OVERDUE_COUNT = """
            SELECT COUNT(*)
            FROM loan
            WHERE returned_at IS NULL AND due_date < :today
            """;

    private static final String SQL_OVERDUE_SELECT = """
            SELECT l.id AS loan_id, r.id AS reader_id, r.card_no, r.name AS reader_name, r.phone,
                   b.id AS book_id, b.title, b.isbn, c.barcode, l.borrow_date, l.due_date
            FROM loan l
            JOIN reader r ON r.id = l.reader_id
            JOIN book_copy c ON c.id = l.copy_id
            JOIN book b ON b.id = c.book_id
            WHERE l.returned_at IS NULL AND l.due_date < :today
            """;

    // overdueDays = today - due_date（today 为常量），按 overdueDays 排序等价于按 due_date 反向排序；
    // 附加 id 作为稳定平局项，保证分页确定性。两个子句都是常量，不含用户输入。
    private static final String ORDER_DUE_ASC = " ORDER BY l.due_date ASC, l.id ASC";
    private static final String ORDER_DUE_DESC = " ORDER BY l.due_date DESC, l.id ASC";
    private static final String LIMIT_OFFSET = " LIMIT :limit OFFSET :offset";

    /** 逾期报表行（日期保持 TEXT 原样，overdueDays 由服务层按 Clock 计算）。 */
    public record OverdueRow(long loanId, long readerId, String cardNo, String readerName, String phone,
                             long bookId, String title, String isbn, String barcode,
                             String borrowDate, String dueDate) {
    }

    private final JdbcClient jdbc;

    public StatsQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StatsOverview overview(LocalDate today) {
        return jdbc.sql(SQL_OVERVIEW)
                .param("today", today.toString())
                .query((rs, n) -> new StatsOverview(
                        rs.getLong("total_books"), rs.getLong("total_copies"),
                        rs.getLong("available_copies"), rs.getLong("borrowed_copies"),
                        rs.getLong("unavailable_copies"), rs.getLong("total_readers"),
                        rs.getLong("active_readers"), rs.getLong("active_loans"),
                        rs.getLong("overdue_loans"), rs.getLong("borrows_today"),
                        rs.getLong("returns_today")))
                .single();
    }

    /** borrow_date 落在 [from, to] 的借阅条数，按粒度分组（含之后已归还的；续借不产生新行）。 */
    public Map<String, Long> borrowCounts(LocalDate from, LocalDate to, StatsGranularity granularity) {
        return counts(granularity == StatsGranularity.DAY ? SQL_BORROW_BY_DAY : SQL_BORROW_BY_MONTH, from, to);
    }

    /** return_date 落在 [from, to] 的归还条数，按粒度分组。 */
    public Map<String, Long> returnCounts(LocalDate from, LocalDate to, StatsGranularity granularity) {
        return counts(granularity == StatsGranularity.DAY ? SQL_RETURN_BY_DAY : SQL_RETURN_BY_MONTH, from, to);
    }

    public List<TopBook> topBooks(LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(SQL_TOP_BOOKS)
                .param("from", from.toString())
                .param("to", to.toString())
                .param("limit", limit)
                .query((rs, n) -> new TopBook(rs.getLong("book_id"), rs.getString("title"),
                        rs.getString("author"), rs.getString("isbn"), rs.getLong("borrow_count")))
                .list();
    }

    public long countOverdue(LocalDate today) {
        Long count = jdbc.sql(SQL_OVERDUE_COUNT)
                .param("today", today.toString())
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /**
     * 逾期报表分页。sortField 必须已通过白名单（overdueDays、dueDate）；
     * asc 为排序方向。overdueDays 与 due_date 排序方向相反（today 常量）。
     */
    public List<OverdueRow> overduePage(LocalDate today, String sortField, boolean asc, int offset, int size) {
        boolean dueAsc = "dueDate".equals(sortField) ? asc : !asc;
        String sql = SQL_OVERDUE_SELECT + (dueAsc ? ORDER_DUE_ASC : ORDER_DUE_DESC) + LIMIT_OFFSET;
        return jdbc.sql(sql)
                .param("today", today.toString())
                .param("limit", size)
                .param("offset", offset)
                .query((rs, n) -> new OverdueRow(rs.getLong("loan_id"), rs.getLong("reader_id"),
                        rs.getString("card_no"), rs.getString("reader_name"), rs.getString("phone"),
                        rs.getLong("book_id"), rs.getString("title"), rs.getString("isbn"),
                        rs.getString("barcode"), rs.getString("borrow_date"), rs.getString("due_date")))
                .list();
    }

    private Map<String, Long> counts(String sql, LocalDate from, LocalDate to) {
        List<Map.Entry<String, Long>> rows = jdbc.sql(sql)
                .param("from", from.toString())
                .param("to", to.toString())
                .query((rs, n) -> Map.entry(rs.getString("period"), rs.getLong("cnt")))
                .list();
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, Long> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }
}