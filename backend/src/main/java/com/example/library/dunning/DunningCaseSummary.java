package com.example.library.dunning;

import java.time.Instant;
import java.time.LocalDate;

public record DunningCaseSummary(Long loanId, ReaderRef reader, BookRef book, String copyBarcode,
                                 LocalDate dueDate, String status, Instant closedAt, String closeReason,
                                 long recordCount, Instant lastRecordedAt) {
    public record ReaderRef(Long id, String cardNo, String name) {}
    public record BookRef(Long id, String title) {}
}
