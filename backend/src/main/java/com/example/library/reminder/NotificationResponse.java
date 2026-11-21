package com.example.library.reminder;

import java.time.Instant;
import java.time.LocalDate;
import com.example.library.domain.enums.*;

/** overdueDays is generation-time snapshot; seq is 1-based overdue cycle, otherwise null. */
public record NotificationResponse(Long id, NotificationType type, String title, String content,
        Long loanId, BookRef book, String copyBarcode, LocalDate dueDate, Long overdueDays, Long seq,
        Instant createdAt, Instant readAt, LoanState loanState) {
    public record BookRef(Long id, String title) {}
    public record LoanState(LoanStatus status, LocalDate currentDueDate) {}
}
