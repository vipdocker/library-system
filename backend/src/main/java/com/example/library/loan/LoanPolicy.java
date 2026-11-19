package com.example.library.loan;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.example.library.common.config.LibraryProperties;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.LoanStatus;
import com.example.library.domain.enums.ReaderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 借阅业务规则纯函数（design-v0.1 第 9.6 节）。
 * 阈值全部来自 lib.loan.* 配置（D1），不写死；时间全部来自注入的 Clock（K7，时区默认 Asia/Shanghai）。
 * 逾期判定：未还且 today > due_date（应还日当天仍算按期，第 9.1 节）。
 * renewable 只表达业务规则层面的可续借性；调用者身份（D2 开关、归属）由 LoanViewMapper 叠加。
 */
@Component
public class LoanPolicy {

    private final LibraryProperties.Loan properties;
    private final Clock clock;

    @Autowired
    public LoanPolicy(LibraryProperties libraryProperties, Clock clock) {
        this(libraryProperties.getLoan(), clock);
    }

    public LoanPolicy(LibraryProperties.Loan properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 业务日期：按 Clock 时区（默认 Asia/Shanghai）取今天。 */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 借期：due_date = borrow_date + loanDays（第 9.1 节）。 */
    public LocalDate dueDateFor(LocalDate borrowDate) {
        return borrowDate.plusDays(properties.getLoanDays());
    }

    /** 续借顺延：从当前应还日起算 + renewDays（D1，不是从今天起算）。 */
    public LocalDate renewedDueDate(LocalDate currentDue) {
        return currentDue.plusDays(properties.getRenewDays());
    }

    /** 逾期 = 未还且 today > due_date；应还日当天不算逾期。 */
    public boolean isOverdue(Loan loan, LocalDate today) {
        return loan.getReturnedAt() == null && today.isAfter(loan.getDueDate());
    }

    /** 未还：max(0, today - due_date)；已还：max(0, return_date - due_date)（第 6 节派生值）。 */
    public long overdueDays(Loan loan, LocalDate today) {
        LocalDate end = loan.getReturnDate() != null ? loan.getReturnDate() : today;
        return Math.max(0, ChronoUnit.DAYS.between(loan.getDueDate(), end));
    }

    /** 派生状态：RETURNED / OVERDUE / ACTIVE（第 6 节）。 */
    public LoanStatus statusOf(Loan loan, LocalDate today) {
        if (loan.getReturnedAt() != null) {
            return LoanStatus.RETURNED;
        }
        return isOverdue(loan, today) ? LoanStatus.OVERDUE : LoanStatus.ACTIVE;
    }

    /**
     * 业务层面可续借（第 9.4 节校验 2–6）：未归还、读者 ACTIVE、本条未逾期、
     * 读者没有其他逾期、renew_count < max。hasOtherOverdue 指“除本条外”的逾期。
     */
    public boolean renewable(Loan loan, Reader reader, boolean hasOtherOverdue, LocalDate today) {
        return loan.getReturnedAt() == null
                && reader.getStatus() == ReaderStatus.ACTIVE
                && !isOverdue(loan, today)
                && !hasOtherOverdue
                && loan.getRenewCount() < properties.getMaxRenewals();
    }

    public int maxRenewals() {
        return properties.getMaxRenewals();
    }
}