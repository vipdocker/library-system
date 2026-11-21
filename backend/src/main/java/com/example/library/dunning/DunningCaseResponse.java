package com.example.library.dunning;

import java.time.Instant;
import java.util.List;

public record DunningCaseResponse(Long loanId, String status, Instant closedAt, String closeReason,
                                  List<DunningRecordResponse> records) {}
