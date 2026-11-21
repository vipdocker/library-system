package com.example.library.reminder;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import com.example.library.common.error.*;
import com.example.library.common.web.*;
import com.example.library.domain.entity.Notification;
import com.example.library.domain.enums.*;
import com.example.library.domain.repository.*;
import com.example.library.loan.LoanPolicy;
import com.example.library.security.LibraryUserPrincipal;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final LoanRepository loans;
    private final DunningRecordRepository records;
    private final LoanPolicy policy;
    private final Clock clock;
    public NotificationService(NotificationRepository notifications, LoanRepository loans,
                               DunningRecordRepository records, LoanPolicy policy, Clock clock) {
        this.notifications = notifications; this.loans = loans; this.records = records;
        this.policy = policy; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> inbox(LibraryUserPrincipal principal, boolean unreadOnly, Integer page, Integer size) {
        var pageable = PageRequestParams.toPageable(page, size, null, Set.of(),
            Sort.by(Sort.Direction.DESC,"createdAt").and(Sort.by(Sort.Direction.DESC,"id")));
        return PageResponse.from(notifications.inbox(readerId(principal), unreadOnly, pageable), this::response);
    }
    @Transactional(readOnly = true)
    public long unreadCount(LibraryUserPrincipal principal) {
        return notifications.countByReaderIdAndReadAtIsNull(readerId(principal));
    }
    @Transactional
    public void markRead(LibraryUserPrincipal principal, Long id) {
        if(notifications.markRead(id, readerId(principal), clock.instant()) == 0)
            throw ApiException.of(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
    @Transactional
    public int markAllRead(LibraryUserPrincipal principal) {
        return notifications.markAllRead(readerId(principal), clock.instant());
    }
    /**
     * Caller must first saveAndFlush the DunningRecord, in its existing write transaction.
     * Returns the notice ID and links the record. The caller owns overdue/return eligibility
     * and write-lock acquisition before reads. No note or contact method enters the template.
     * MANDATORY keeps record + notification atomic and forbids standalone invocation.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Long notifyDunning(Long loanId, Long dunningRecordId) {
        var record = records.findById(dunningRecordId)
            .orElseThrow(() -> new IllegalArgumentException("Dunning record must be persisted first"));
        if (!record.getLoan().getId().equals(loanId))
            throw new IllegalArgumentException("Dunning record does not belong to loan");
        if (record.getNotificationId() != null) return record.getNotificationId();
        var loan = loans.findById(loanId).orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
        String content = "您借阅的《" + loan.getCopy().getBook().getTitle() + "》（条码：" + loan.getCopy().getBarcode()
            + "），应还日为 " + loan.getDueDate() + "，图书馆提醒您及时归还。";
        var notice = notifications.saveAndFlush(new Notification(loan, NotificationType.DUNNING, null,
            "图书催还提醒", content, record.getCreatedAt()));
        record.setNotificationId(notice.getId());
        records.saveAndFlush(record);
        return notice.getId();
    }

    private Long readerId(LibraryUserPrincipal principal) {
        if(principal.getRole() != Role.READER) throw ApiException.of(ErrorCode.FORBIDDEN);
        if(principal.getReaderId() == null) throw ApiException.of(ErrorCode.READER_PROFILE_NOT_FOUND);
        return principal.getReaderId();
    }
    private NotificationResponse response(Notification n) {
        var loan = n.getLoan();
        Long days = n.getType() == NotificationType.DUE_SOON ? null : Math.max(0,
            ChronoUnit.DAYS.between(n.getDueDate(), n.getCreatedAt().atZone(clock.getZone()).toLocalDate()));
        return new NotificationResponse(n.getId(),n.getType(),n.getTitle(),n.getContent(),loan.getId(),
            new NotificationResponse.BookRef(loan.getCopy().getBook().getId(),loan.getCopy().getBook().getTitle()),
            loan.getCopy().getBarcode(),n.getDueDate(),days,n.getType() == NotificationType.OVERDUE ? n.getSeq() : null,
            n.getCreatedAt(),n.getReadAt(),new NotificationResponse.LoanState(policy.statusOf(loan,policy.today()),loan.getDueDate()));
    }
}
