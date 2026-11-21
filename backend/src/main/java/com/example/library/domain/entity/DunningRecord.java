package com.example.library.domain.entity;

import java.time.Instant;
import com.example.library.domain.converter.EpochMillisInstantConverter;
import com.example.library.domain.enums.DunningMethod;
import jakarta.persistence.*;

/** Append-only manual history. Case state is derived from loan.returnedAt. */
@Entity
@Table(name = "dunning_record")
public class DunningRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false) private Loan loan;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DunningMethod method;
    private String note;
    @Column(name = "operator_id", nullable = false) private Long operatorId;
    @Convert(converter = EpochMillisInstantConverter.class) @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "notification_id") private Long notificationId;

    protected DunningRecord() {}
    public DunningRecord(Loan loan, DunningMethod method, String note, Long operatorId, Instant createdAt) {
        this.loan = loan; this.method = method; this.note = note;
        this.operatorId = operatorId; this.createdAt = createdAt;
    }
    public Long getId() { return id; }
    public Loan getLoan() { return loan; }
    public DunningMethod getMethod() { return method; }
    public String getNote() { return note; }
    public Long getOperatorId() { return operatorId; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
}
