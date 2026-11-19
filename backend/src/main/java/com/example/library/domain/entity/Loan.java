package com.example.library.domain.entity;

import java.time.Instant;
import java.time.LocalDate;

import com.example.library.domain.converter.EpochMillisInstantConverter;
import com.example.library.domain.converter.IsoLocalDateConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 借阅（loan）。status/overdueDays 为派生值不落库。
 * copy/reader 关联用于 JPQL join；LAZY，服务层在事务内访问。
 * 经办人只存 id（审计字段，design-v0.1 第 12 节）。
 * 并发正确性依赖条件 UPDATE + ux_loan_active_copy 部分唯一索引（第 9.5 节）。
 */
@Entity
@Table(name = "loan")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "copy_id", nullable = false)
    private BookCopy copy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reader_id", nullable = false)
    private Reader reader;

    @Convert(converter = EpochMillisInstantConverter.class)
    @Column(name = "borrowed_at", nullable = false)
    private Instant borrowedAt;

    @Convert(converter = IsoLocalDateConverter.class)
    @Column(name = "borrow_date", nullable = false)
    private LocalDate borrowDate;

    @Convert(converter = IsoLocalDateConverter.class)
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Convert(converter = EpochMillisInstantConverter.class)
    @Column(name = "returned_at")
    private Instant returnedAt;

    @Convert(converter = IsoLocalDateConverter.class)
    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "renew_count", nullable = false)
    private int renewCount;

    @Column(name = "borrow_operator_id", nullable = false)
    private Long borrowOperatorId;

    @Column(name = "return_operator_id")
    private Long returnOperatorId;

    @Column(name = "last_renew_operator_id")
    private Long lastRenewOperatorId;

    protected Loan() {
    }

    public Loan(BookCopy copy, Reader reader, Instant borrowedAt, LocalDate borrowDate, LocalDate dueDate,
                Long borrowOperatorId) {
        this.copy = copy;
        this.reader = reader;
        this.borrowedAt = borrowedAt;
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
        this.borrowOperatorId = borrowOperatorId;
        this.renewCount = 0;
    }

    public Long getId() {
        return id;
    }

    public BookCopy getCopy() {
        return copy;
    }

    public void setCopy(BookCopy copy) {
        this.copy = copy;
    }

    public Reader getReader() {
        return reader;
    }

    public void setReader(Reader reader) {
        this.reader = reader;
    }

    public Instant getBorrowedAt() {
        return borrowedAt;
    }

    public void setBorrowedAt(Instant borrowedAt) {
        this.borrowedAt = borrowedAt;
    }

    public LocalDate getBorrowDate() {
        return borrowDate;
    }

    public void setBorrowDate(LocalDate borrowDate) {
        this.borrowDate = borrowDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(Instant returnedAt) {
        this.returnedAt = returnedAt;
    }

    public LocalDate getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(LocalDate returnDate) {
        this.returnDate = returnDate;
    }

    public int getRenewCount() {
        return renewCount;
    }

    public void setRenewCount(int renewCount) {
        this.renewCount = renewCount;
    }

    public Long getBorrowOperatorId() {
        return borrowOperatorId;
    }

    public void setBorrowOperatorId(Long borrowOperatorId) {
        this.borrowOperatorId = borrowOperatorId;
    }

    public Long getReturnOperatorId() {
        return returnOperatorId;
    }

    public void setReturnOperatorId(Long returnOperatorId) {
        this.returnOperatorId = returnOperatorId;
    }

    public Long getLastRenewOperatorId() {
        return lastRenewOperatorId;
    }

    public void setLastRenewOperatorId(Long lastRenewOperatorId) {
        this.lastRenewOperatorId = lastRenewOperatorId;
    }
}