package com.example.library.dunning;

import java.time.Instant;
import com.example.library.domain.enums.DunningMethod;

public record DunningRecordResponse(Long id, Long loanId, DunningMethod method, String note,
                                    OperatorRef operator, Instant createdAt, Long notificationId) {
    public record OperatorRef(Long id, String username) {}
}
