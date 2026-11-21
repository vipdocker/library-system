package com.example.library.reminder;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One short write transaction per loan. The FIRST SQL statement is INSERT SELECT:
 * no read-to-write lock upgrade, and eligibility/snapshot/deduplication are atomic.
 * Return or renewal committed first is seen here. A later return keeps the existing notice.
 * Only the explicit automatic unique key conflict is ignored, never other SQL failures.
 */
@Component
public class ReminderWriter {
    private final JdbcTemplate jdbc;
    private final ReminderProperties properties;
    public ReminderWriter(JdbcTemplate jdbc, ReminderProperties properties) {
        this.jdbc = jdbc; this.properties = properties;
    }

    @Transactional
    public int scanLoan(Long loanId, LocalDate today, Instant now) {
        return jdbc.update("""
            INSERT INTO notification(reader_id,loan_id,type,due_date,seq,title,content,created_at)
            SELECT l.reader_id,l.id,
                CASE WHEN l.due_date >= ? THEN 'DUE_SOON' ELSE 'OVERDUE' END,
                l.due_date,
                CASE WHEN l.due_date >= ? THEN 0
                     ELSE CAST((julianday(?) - julianday(l.due_date) - 1) / ? AS INTEGER) + 1 END,
                CASE WHEN l.due_date >= ? THEN '图书即将到期' ELSE '图书逾期提醒' END,
                '您借阅的《' || b.title || '》（条码：' || c.barcode || '），应还日为 ' || l.due_date ||
                CASE WHEN l.due_date >= ? THEN '，请及时归还。' ELSE '，已逾期，请及时归还。' END,
                ?
            FROM loan l JOIN book_copy c ON c.id=l.copy_id JOIN book b ON b.id=c.book_id
            WHERE l.id=? AND l.returned_at IS NULL AND l.due_date <= ?
            ON CONFLICT(loan_id,type,due_date,seq) WHERE type IN ('DUE_SOON','OVERDUE') DO NOTHING
            """, today.toString(), today.toString(), today.toString(), properties.getOverdueRepeatDays(),
            today.toString(), today.toString(), now.toEpochMilli(), loanId,
            today.plusDays(properties.getDueSoonDays()).toString());
    }
}
