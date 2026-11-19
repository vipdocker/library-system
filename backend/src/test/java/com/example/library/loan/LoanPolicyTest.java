package com.example.library.loan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.example.library.common.config.LibraryProperties;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.LoanStatus;
import com.example.library.domain.enums.ReaderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoanPolicy 纯函数单测（design-v0.1 第 9.6 节；AC-LOAN-01/04/05/06）。
 * 不启动 Spring：直接 new LoanPolicy(LoanProperties, Clock)。
 * 阈值全部来自配置（D1：默认 5/30/1/30），另用自定义配置证明没有写死。
 */
class LoanPolicyTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-11-17T02:00:00Z"); // 上海 2026-11-17 10:00
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final LibraryProperties.Loan props = new LibraryProperties.Loan();
    private final LoanPolicy policy = new LoanPolicy(props, Clock.fixed(NOW, SHANGHAI));

    private static Reader reader(ReaderStatus status) {
        return new Reader(1L, "R000001", "张三", status, NOW);
    }

    private static Loan loan(LocalDate borrowDate, LocalDate dueDate, LocalDate returnDate, int renewCount) {
        BookCopy copy = new BookCopy(null, "C00000001", CopyStatus.BORROWED, NOW);
        Loan loan = new Loan(copy, reader(ReaderStatus.ACTIVE),
                borrowDate.atStartOfDay(SHANGHAI).toInstant(), borrowDate, dueDate, 99L);
        loan.setRenewCount(renewCount);
        if (returnDate != null) {
            loan.setReturnDate(returnDate);
            loan.setReturnedAt(returnDate.atStartOfDay(SHANGHAI).toInstant());
        }
        return loan;
    }

    @Test
    @DisplayName("AC-LOAN-01：dueDateFor = borrowDate + loanDays（默认 30 天）")
    void dueDateForDefault() {
        assertThat(policy.dueDateFor(TODAY)).isEqualTo(LocalDate.of(2026, 12, 17));
    }

    @Test
    @DisplayName("AC-LOAN-01：借期取自配置，不是写死的 30")
    void dueDateForConfigDriven() {
        props.setLoanDays(7);
        assertThat(policy.dueDateFor(TODAY)).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    @Test
    @DisplayName("AC-LOAN-01：today() 按 Clock 时区取业务日期")
    void todayUsesClockZone() {
        assertThat(policy.today()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("AC-LOAN-05：renewedDueDate 从当前应还日顺延 renewDays（默认 30 天）")
    void renewedDueDateFromCurrentDue() {
        LocalDate currentDue = LocalDate.of(2026, 12, 17);
        assertThat(policy.renewedDueDate(currentDue)).isEqualTo(LocalDate.of(2027, 1, 16));
    }

    @Test
    @DisplayName("AC-LOAN-05：续借顺延天数取自配置")
    void renewedDueDateConfigDriven() {
        props.setRenewDays(14);
        assertThat(policy.renewedDueDate(LocalDate.of(2026, 12, 17))).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("AC-LOAN-06：应还日当天不算逾期（status=ACTIVE，overdueDays=0）")
    void dueTodayIsNotOverdue() {
        Loan loan = loan(TODAY.minusDays(30), TODAY, null, 0);
        assertThat(policy.isOverdue(loan, TODAY)).isFalse();
        assertThat(policy.statusOf(loan, TODAY)).isEqualTo(LoanStatus.ACTIVE);
        assertThat(policy.overdueDays(loan, TODAY)).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-06：应还日次日才算逾期（status=OVERDUE，overdueDays=1）")
    void dueYesterdayIsOverdueByOneDay() {
        Loan loan = loan(TODAY.minusDays(31), TODAY.minusDays(1), null, 0);
        assertThat(policy.isOverdue(loan, TODAY)).isTrue();
        assertThat(policy.statusOf(loan, TODAY)).isEqualTo(LoanStatus.OVERDUE);
        assertThat(policy.overdueDays(loan, TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-LOAN-06：时区边界——UTC D 日 15:59:59（上海 D 日 23:59:59）未逾期")
    void timezoneBoundaryBeforeMidnight() {
        LoanPolicy beforeMidnight = new LoanPolicy(props,
                Clock.fixed(Instant.parse("2026-11-17T15:59:59Z"), SHANGHAI));
        Loan loan = loan(TODAY.minusDays(30), TODAY, null, 0);
        assertThat(beforeMidnight.today()).isEqualTo(TODAY);
        assertThat(beforeMidnight.isOverdue(loan, beforeMidnight.today())).isFalse();
    }

    @Test
    @DisplayName("AC-LOAN-06：时区边界——UTC D 日 16:00:00（上海 D+1 日 00:00）已判逾期")
    void timezoneBoundaryAtMidnight() {
        LoanPolicy atMidnight = new LoanPolicy(props,
                Clock.fixed(Instant.parse("2026-11-17T16:00:00Z"), SHANGHAI));
        Loan loan = loan(TODAY.minusDays(30), TODAY, null, 0);
        assertThat(atMidnight.today()).isEqualTo(TODAY.plusDays(1));
        assertThat(atMidnight.isOverdue(loan, atMidnight.today())).isTrue();
        assertThat(atMidnight.overdueDays(loan, atMidnight.today())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-LOAN-04：逾期天数——未还按 today-dueDate，已还按 returnDate-dueDate")
    void overdueDaysForActiveAndReturned() {
        Loan active = loan(TODAY.minusDays(40), TODAY.minusDays(10), null, 0);
        assertThat(policy.overdueDays(active, TODAY)).isEqualTo(10);

        Loan returnedLate = loan(TODAY.minusDays(40), TODAY.minusDays(10), TODAY.minusDays(7), 0);
        assertThat(policy.overdueDays(returnedLate, TODAY)).isEqualTo(3);
        assertThat(policy.statusOf(returnedLate, TODAY)).isEqualTo(LoanStatus.RETURNED);
        assertThat(policy.isOverdue(returnedLate, TODAY)).isFalse();

        Loan returnedEarly = loan(TODAY.minusDays(10), TODAY.plusDays(20), TODAY, 0);
        assertThat(policy.overdueDays(returnedEarly, TODAY)).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-05：renewable——全部条件满足时为 true")
    void renewableWhenAllConditionsMet() {
        Loan loan = loan(TODAY.minusDays(10), TODAY.plusDays(20), null, 0);
        assertThat(policy.renewable(loan, reader(ReaderStatus.ACTIVE), false, TODAY)).isTrue();
    }

    @Test
    @DisplayName("AC-LOAN-05：renewable——已归还 / 读者停用 / 本条逾期 / 其他逾期 / 次数用尽 时为 false")
    void renewableFalseForEachBlockingCondition() {
        Loan eligible = loan(TODAY.minusDays(10), TODAY.plusDays(20), null, 0);
        assertThat(policy.renewable(eligible, reader(ReaderStatus.DISABLED), false, TODAY)).isFalse();
        assertThat(policy.renewable(eligible, reader(ReaderStatus.ACTIVE), true, TODAY)).isFalse();

        Loan returned = loan(TODAY.minusDays(10), TODAY.plusDays(20), TODAY, 0);
        assertThat(policy.renewable(returned, reader(ReaderStatus.ACTIVE), false, TODAY)).isFalse();

        Loan overdue = loan(TODAY.minusDays(40), TODAY.minusDays(1), null, 0);
        assertThat(policy.renewable(overdue, reader(ReaderStatus.ACTIVE), false, TODAY)).isFalse();

        Loan usedUp = loan(TODAY.minusDays(10), TODAY.plusDays(50), null, 1);
        assertThat(policy.renewable(usedUp, reader(ReaderStatus.ACTIVE), false, TODAY)).isFalse();
    }

    @Test
    @DisplayName("AC-LOAN-05：maxRenewals=0（配置禁止续借）时 renewable 恒为 false")
    void renewableFalseWhenMaxRenewalsZero() {
        props.setMaxRenewals(0);
        Loan loan = loan(TODAY.minusDays(10), TODAY.plusDays(20), null, 0);
        assertThat(policy.renewable(loan, reader(ReaderStatus.ACTIVE), false, TODAY)).isFalse();
        assertThat(policy.maxRenewals()).isZero();
    }

    @Test
    @DisplayName("AC-LOAN-05：续借次数上限取自配置（maxRenewals=2 时第 2 次仍可续借）")
    void maxRenewalsConfigDriven() {
        props.setMaxRenewals(2);
        Loan onceRenewed = loan(TODAY.minusDays(10), TODAY.plusDays(50), null, 1);
        assertThat(policy.renewable(onceRenewed, reader(ReaderStatus.ACTIVE), false, TODAY)).isTrue();
        assertThat(policy.maxRenewals()).isEqualTo(2);
    }
}