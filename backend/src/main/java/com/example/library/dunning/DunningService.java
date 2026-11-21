package com.example.library.dunning;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.example.library.common.error.*;
import com.example.library.common.util.Texts;
import com.example.library.common.web.*;
import com.example.library.domain.entity.DunningRecord;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.*;
import com.example.library.loan.LoanPolicy;
import com.example.library.reminder.NotificationService;
import com.example.library.security.LibraryUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DunningService {
    private static final Logger log = LoggerFactory.getLogger(DunningService.class);
    private final DunningRecordRepository records;
    private final LoanRepository loans;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final LoanPolicy policy;
    private final Clock clock;

    public DunningService(DunningRecordRepository records, LoanRepository loans, AppUserRepository users,
                          NotificationService notifications, JdbcTemplate jdbc, LoanPolicy policy, Clock clock) {
        this.records = records;
        this.loans = loans;
        this.users = users;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public DunningRecordResponse register(Long loanId, DunningRequest request, LibraryUserPrincipal actor) {
        if (actor.getRole() != Role.ADMIN) throw ApiException.of(ErrorCode.FORBIDDEN);
        // First SQL acquires SQLite's write lock before eligibility reads, without changing loan state.
        jdbc.update("UPDATE loan SET renew_count=renew_count WHERE id=?", loanId);
        Loan loan = loan(loanId);
        if (loan.getReturnedAt() != null) throw ApiException.of(ErrorCode.LOAN_ALREADY_RETURNED);
        if (!policy.isOverdue(loan, policy.today())) throw ApiException.of(ErrorCode.LOAN_NOT_OVERDUE);
        var record = records.saveAndFlush(new DunningRecord(loan, request.method(), request.note(),
                actor.getUserId(), clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        notifications.notifyDunning(loanId, record.getId());
        log.info("dunning.register loanId={} recordId={} operatorId={} notificationId={}",
                loanId, record.getId(), actor.getUserId(), record.getNotificationId());
        return response(record, new DunningRecordResponse.OperatorRef(actor.getUserId(), actor.getUsername()));
    }

    @Transactional(readOnly = true)
    public DunningCaseResponse history(Long loanId) {
        var loan = loan(loanId);
        var history = records.findByLoanIdOrderByCreatedAtAscIdAsc(loanId);
        var operatorIds = history.stream().map(DunningRecord::getOperatorId).distinct().toList();
        var operators = users.findAllById(operatorIds).stream().collect(Collectors.toMap(u -> u.getId(),
                u -> new DunningRecordResponse.OperatorRef(u.getId(), u.getUsername())));
        return new DunningCaseResponse(loanId, status(loan), loan.getReturnedAt(), closeReason(loan),
                history.stream().map(r -> response(r, operators.get(r.getOperatorId()))).toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<DunningCaseSummary> cases(String status, Integer page, Integer size, String sort) {
        String filter = Texts.trimToNull(status);
        if (filter != null && !Set.of("OPEN", "CLOSED").contains(filter)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                    Map.of(), List.of(new ErrorResponse.FieldErrorItem("status", "status 只能为 OPEN 或 CLOSED")));
        }
        var pageable = PageRequestParams.toPageable(page, size, sort, Set.of(), Sort.unsorted());
        return PageResponse.from(records.findCases(filter == null ? "ALL" : filter, pageable), row -> {
            var loan = row.getLoan();
            var reader = loan.getReader();
            var copy = loan.getCopy();
            return new DunningCaseSummary(loan.getId(),
                    new DunningCaseSummary.ReaderRef(reader.getId(), reader.getCardNo(), reader.getName()),
                    new DunningCaseSummary.BookRef(copy.getBook().getId(), copy.getBook().getTitle()),
                    copy.getBarcode(), loan.getDueDate(), status(loan), loan.getReturnedAt(), closeReason(loan),
                    row.getRecordCount(), row.getLastRecordedAt());
        });
    }

    private Loan loan(Long id) {
        return loans.findById(id).orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
    }
    private static String status(Loan loan) { return loan.getReturnedAt() == null ? "OPEN" : "CLOSED"; }
    private static String closeReason(Loan loan) { return loan.getReturnedAt() == null ? null : "RETURNED"; }
    private static DunningRecordResponse response(DunningRecord record, DunningRecordResponse.OperatorRef operator) {
        return new DunningRecordResponse(record.getId(), record.getLoan().getId(), record.getMethod(),
                record.getNote(), operator, record.getCreatedAt(), record.getNotificationId());
    }
}
