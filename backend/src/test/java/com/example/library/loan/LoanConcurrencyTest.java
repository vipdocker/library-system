package com.example.library.loan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.loan.dto.LoanResponse;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-LOAN-03 并发安全（design-v0.1 第 9.5 节、第 13 节并发测试策略）：
 * ExecutorService + CountDownLatch，直接调用 Service（每个线程独立事务）。
 * 第一层 Hikari 单连接串行化；第二层条件 UPDATE + ux_loan_active_copy 部分唯一索引。
 * 本测试类需连续运行 3 次全部通过（交接包验证要求）。
 */
class LoanConcurrencyTest extends LoanTestBase {

    @Autowired
    private LoanService loanService;

    /** 单次调用结果：成功带 loanId，失败带错误码。 */
    private record Outcome(Long loanId, ErrorCode error) {
    }

    private List<Outcome> runConcurrently(int threads, Callable<Outcome> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (ApiException ex) {
                        return new Outcome(null, ex.getCode());
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(threads);
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("AC-LOAN-03：10 个线程并发借同一副本——恰好 1 人成功，其余 COPY_NOT_AVAILABLE，在借记录恰好 1 条")
    void tenThreadsBorrowSameCopy() throws Exception {
        BookCopy copy = newCopy(newBook("并发同副本"));
        ReaderFixture[] readers = new ReaderFixture[10];
        for (int i = 0; i < 10; i++) {
            readers[i] = testDataFactory.reader("conc-copy-" + i, READER_PASSWORD, "并发读者" + i,
                    ReaderStatus.ACTIVE);
        }
        Long operatorId = adminUser.getId();
        List<Outcome> outcomes = runConcurrently(10, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Outcome call() {
                // 每个线程用不同读者，排除 D4②/上限干扰，专测副本竞争
                ReaderFixture reader = readers[next.getAndIncrement()];
                LoanResponse response = loanService.borrow(reader.reader().getCardNo(), copy.getBarcode(), operatorId);
                return new Outcome(response.id(), null);
            }
        });
        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        List<ErrorCode> errors = outcomes.stream().map(Outcome::error).filter(e -> e != null).toList();
        assertThat(successes).isEqualTo(1);
        assertThat(errors).as("失败只允许 COPY_NOT_AVAILABLE，实际：%s", errors)
                .hasSize(9).allMatch(e -> e == ErrorCode.COPY_NOT_AVAILABLE);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan WHERE copy_id = ? AND returned_at IS NULL",
                Long.class, copy.getId())).isEqualTo(1);
        assertThat(copyStatus(copy.getId())).isEqualTo("BORROWED");
        assertThat(loanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-LOAN-03：同一读者并发借 8 个不同副本（上限 5）——成功数不超过 5，最终在借数不超过 5")
    void concurrentBorrowsRespectLoanLimit() throws Exception {
        BookCopy[] copies = new BookCopy[8];
        for (int i = 0; i < 8; i++) {
            copies[i] = newCopy(newBook("并发上限书" + i));
        }
        String cardNo = readerA.reader().getCardNo();
        Long operatorId = adminUser.getId();
        List<Outcome> outcomes = runConcurrently(8, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Outcome call() {
                BookCopy copy = copies[next.getAndIncrement()];
                LoanResponse response = loanService.borrow(cardNo, copy.getBarcode(), operatorId);
                return new Outcome(response.id(), null);
            }
        });
        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        List<ErrorCode> errors = outcomes.stream().map(Outcome::error).filter(e -> e != null).toList();
        assertThat(errors).as("失败只允许 LOAN_LIMIT_EXCEEDED，实际：%s", errors)
                .allMatch(e -> e == ErrorCode.LOAN_LIMIT_EXCEEDED);
        assertThat(successes).isLessThanOrEqualTo(5);
        assertThat(successes + errors.size()).isEqualTo(8);
        long active = loanRepositoryCountActive(readerA.reader().getId());
        assertThat(active).isEqualTo(successes).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("AC-LOAN-03：同一借阅并发还两次（10 线程）——恰好 1 次成功，其余 LOAN_ALREADY_RETURNED")
    void concurrentDoubleReturn() throws Exception {
        BookCopy copy = newCopy(newBook("并发还书"));
        Loan loan = activeLoan(copy, readerA, today().minusDays(10), today().plusDays(20));
        Long operatorId = adminUser.getId();
        List<Outcome> outcomes = runConcurrently(10, () -> {
            LoanResponse response = loanService.returnLoan(loan.getId(), operatorId);
            return new Outcome(response.id(), null);
        });
        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        List<ErrorCode> errors = outcomes.stream().map(Outcome::error).filter(e -> e != null).toList();
        assertThat(successes).isEqualTo(1);
        assertThat(errors).as("失败只允许 LOAN_ALREADY_RETURNED，实际：%s", errors)
                .hasSize(9).allMatch(e -> e == ErrorCode.LOAN_ALREADY_RETURNED);
        assertThat(copyStatus(copy.getId())).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan WHERE copy_id = ? AND returned_at IS NULL",
                Long.class, copy.getId())).isZero();
    }

    private long loanRepositoryCountActive(Long readerId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan WHERE reader_id = ? AND returned_at IS NULL",
                Long.class, readerId);
    }
}