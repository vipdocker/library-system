package com.example.library.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import com.example.library.domain.converter.*;
import com.example.library.domain.enums.NotificationType;
import jakarta.persistence.*;

@Entity
@Table(name = "notification")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "reader_id", nullable = false)
    private Long readerId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private NotificationType type;
    @Convert(converter = IsoLocalDateConverter.class) @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    private Long seq;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String content;
    @Convert(converter = EpochMillisInstantConverter.class) @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Convert(converter = EpochMillisInstantConverter.class) @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {}
    public Notification(Loan loan, NotificationType type, Long seq, String title, String content, Instant createdAt) {
        this.loan = loan; this.readerId = loan.getReader().getId(); this.type = type;
        this.dueDate = loan.getDueDate(); this.seq = seq; this.title = title;
        this.content = content; this.createdAt = createdAt;
    }
    public Long getId() { return id; }
    public Long getReaderId() { return readerId; }
    public Loan getLoan() { return loan; }
    public NotificationType getType() { return type; }
    public LocalDate getDueDate() { return dueDate; }
    public Long getSeq() { return seq; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
