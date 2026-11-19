package com.example.library.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.example.library.domain.entity.AppUser;
import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.CategoryRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.ReaderRepository;
import com.example.library.support.IntegrationTestBase;
import com.example.library.support.TestDataFactory.ReaderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository 冒烟测试：覆盖 T01 预置的全部跨模块方法（后续任务只读依赖这些签名与语义）。
 * 支撑 AC-LOAN-02/03/04/05 的数据层前提；同时验证 Converter 往返与部分唯一索引。
 */
class RepositorySmokeTest extends IntegrationTestBase {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ReaderRepository readerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookCopyRepository bookCopyRepository;
    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private LocalDate today() {
        return LocalDate.now(mutableClock);
    }

    @Test
    @DisplayName("T01 冒烟：AppUserRepository.findByUsernameIgnoreCase/existsByUsernameIgnoreCase/existsByRole")
    void appUserRepositoryMethods() {
        AppUser admin = testDataFactory.admin("SmokeAdmin", "Admin@12345");
        testDataFactory.reader("smoke-reader", "Reader@123", "冒烟读者", ReaderStatus.ACTIVE);
        assertThat(appUserRepository.findByUsernameIgnoreCase("smokeadmin")).isPresent()
                .get().extracting(AppUser::getId).isEqualTo(admin.getId());
        assertThat(appUserRepository.findByUsernameIgnoreCase("nobody")).isEmpty();
        assertThat(appUserRepository.existsByUsernameIgnoreCase("SMOKE-READER")).isTrue();
        assertThat(appUserRepository.existsByUsernameIgnoreCase("missing")).isFalse();
        assertThat(appUserRepository.existsByRole(Role.ADMIN)).isTrue();
        assertThat(appUserRepository.existsByRole(Role.READER)).isTrue();
    }

    @Test
    @DisplayName("T01 冒烟：ReaderRepository.findByCardNo/findByUserId；卡号格式 R%06d")
    void readerRepositoryMethods() {
        ReaderFixture fixture = testDataFactory.reader("smoke-reader", "Reader@123", "冒烟读者", ReaderStatus.ACTIVE);
        Reader reader = fixture.reader();
        assertThat(reader.getCardNo()).isEqualTo(String.format("R%06d", reader.getId()));
        assertThat(readerRepository.findByCardNo(reader.getCardNo())).isPresent()
                .get().extracting(Reader::getId).isEqualTo(reader.getId());
        assertThat(readerRepository.findByUserId(fixture.user().getId())).isPresent()
                .get().extracting(Reader::getId).isEqualTo(reader.getId());
        assertThat(readerRepository.findByCardNo("R999999")).isEmpty();
        assertThat(readerRepository.findByUserId(999999L)).isEmpty();
    }

    @Test
    @DisplayName("T01 冒烟：CategoryRepository.existsByNameIgnoreCase；数据库 NOCASE 唯一约束兜底")
    void categoryRepositoryMethods() {
        testDataFactory.category("Sci-Fi");
        assertThat(categoryRepository.existsByNameIgnoreCase("sci-fi")).isTrue();
        assertThat(categoryRepository.existsByNameIgnoreCase("SCI-FI")).isTrue();
        assertThat(categoryRepository.existsByNameIgnoreCase("other")).isFalse();
        assertThatThrownBy(() -> categoryRepository.saveAndFlush(
                new com.example.library.domain.entity.Category("SCI-FI", null, mutableClock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("T01 冒烟：BookRepository.existsByIsbn/existsByCategoryId")
    void bookRepositoryMethods() {
        var category = testDataFactory.category("计算机");
        Book book = testDataFactory.book("9787115428028", "Python编程", "Eric Matthes", category.getId());
        assertThat(bookRepository.existsByIsbn("9787115428028")).isTrue();
        assertThat(bookRepository.existsByIsbn("0000000000000")).isFalse();
        assertThat(bookRepository.existsByCategoryId(category.getId())).isTrue();
        assertThat(bookRepository.existsByCategoryId(999999L)).isFalse();
        assertThat(book.getId()).isNotNull();
    }

    @Test
    @DisplayName("T01 冒烟：BookCopyRepository.findByBarcode/count*/markBorrowedIfAvailable/markAvailableIfBorrowed（条件 UPDATE 行数语义）")
    void bookCopyRepositoryMethods() {
        Book book = testDataFactory.book("9787115428028", "Python编程", "Eric Matthes", null);
        BookCopy c1 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy c2 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy c3 = testDataFactory.copy(book, CopyStatus.UNAVAILABLE);
        assertThat(c1.getBarcode()).isEqualTo(String.format("C%08d", c1.getId()));
        assertThat(bookCopyRepository.findByBarcode(c1.getBarcode())).isPresent();
        assertThat(bookCopyRepository.findByBarcode("C99999999")).isEmpty();
        assertThat(bookCopyRepository.countByBookId(book.getId())).isEqualTo(3);
        assertThat(bookCopyRepository.countByBookIdAndStatus(book.getId(), CopyStatus.AVAILABLE)).isEqualTo(2);
        assertThat(bookCopyRepository.countByBookIdAndStatus(book.getId(), CopyStatus.UNAVAILABLE)).isEqualTo(1);

        Instant now = mutableClock.instant();
        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(bookCopyRepository.markBorrowedIfAvailable(c1.getId(), now)).isEqualTo(1);
            // 已 BORROWED：竞争失败语义
            assertThat(bookCopyRepository.markBorrowedIfAvailable(c1.getId(), now)).isZero();
            // UNAVAILABLE 不可借
            assertThat(bookCopyRepository.markBorrowedIfAvailable(c3.getId(), now)).isZero();
        });
        assertThat(bookCopyRepository.findById(c1.getId())).get()
                .extracting(BookCopy::getStatus).isEqualTo(CopyStatus.BORROWED);

        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(bookCopyRepository.markAvailableIfBorrowed(c1.getId(), now)).isEqualTo(1);
            assertThat(bookCopyRepository.markAvailableIfBorrowed(c1.getId(), now)).isZero();
            // AVAILABLE 状态不满足 BORROWED 条件
            assertThat(bookCopyRepository.markAvailableIfBorrowed(c2.getId(), now)).isZero();
        });
        assertThat(bookCopyRepository.findById(c1.getId())).get()
                .extracting(BookCopy::getStatus).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    @DisplayName("T01 冒烟：LoanRepository exists*/countActive/findActiveByCopyId（支撑 AC-LOAN-02/04）")
    void loanRepositoryExistenceMethods() {
        AppUser admin = testDataFactory.admin("smoke-admin", "Admin@12345");
        ReaderFixture r1 = testDataFactory.reader("smoke-r1", "Reader@123", "读者一", ReaderStatus.ACTIVE);
        ReaderFixture r2 = testDataFactory.reader("smoke-r2", "Reader@123", "读者二", ReaderStatus.ACTIVE);
        Book bookA = testDataFactory.book("9787115428028", "书A", "作者A", null);
        Book bookB = testDataFactory.book("9787115544834", "书B", "作者B", null);
        BookCopy a1 = testDataFactory.copy(bookA, CopyStatus.AVAILABLE);
        BookCopy a2 = testDataFactory.copy(bookA, CopyStatus.AVAILABLE);
        BookCopy b1 = testDataFactory.copy(bookB, CopyStatus.AVAILABLE);

        Loan loan = testDataFactory.activeLoan(a1, r1.reader(), today().minusDays(1), today().plusDays(29),
                admin.getId());

        assertThat(loanRepository.existsByCopyId(a1.getId())).isTrue();
        assertThat(loanRepository.existsByCopyId(a2.getId())).isFalse();
        assertThat(loanRepository.existsByCopyBookId(bookA.getId())).isTrue();
        assertThat(loanRepository.existsByCopyBookId(bookB.getId())).isFalse();
        assertThat(loanRepository.existsActiveByCopyBookId(bookA.getId())).isTrue();
        assertThat(loanRepository.existsByReaderId(r1.reader().getId())).isTrue();
        assertThat(loanRepository.existsByReaderId(r2.reader().getId())).isFalse();
        assertThat(loanRepository.countActiveByReaderId(r1.reader().getId())).isEqualTo(1);
        assertThat(loanRepository.countActiveByReaderId(r2.reader().getId())).isZero();
        // D4②：同一书目另一副本也算“已在借该书目”
        assertThat(loanRepository.existsActiveByReaderIdAndBookId(r1.reader().getId(), bookA.getId())).isTrue();
        assertThat(loanRepository.existsActiveByReaderIdAndBookId(r1.reader().getId(), bookB.getId())).isFalse();
        assertThat(loanRepository.existsActiveByReaderIdAndBookId(r2.reader().getId(), bookA.getId())).isFalse();
        assertThat(loanRepository.findActiveByCopyId(a1.getId())).isPresent()
                .get().extracting(Loan::getId).isEqualTo(loan.getId());
        assertThat(loanRepository.findActiveByCopyId(a2.getId())).isEmpty();
        assertThat(loanRepository.findActiveByCopyId(b1.getId())).isEmpty();

        // 归还后：active 语义全部翻转，历史保留
        transactionTemplate.executeWithoutResult(tx ->
                assertThat(loanRepository.markReturnedIfActive(loan.getId(), mutableClock.instant(), today(),
                        admin.getId())).isEqualTo(1));
        assertThat(loanRepository.existsActiveByCopyBookId(bookA.getId())).isFalse();
        assertThat(loanRepository.countActiveByReaderId(r1.reader().getId())).isZero();
        assertThat(loanRepository.existsActiveByReaderIdAndBookId(r1.reader().getId(), bookA.getId())).isFalse();
        assertThat(loanRepository.findActiveByCopyId(a1.getId())).isEmpty();
        assertThat(loanRepository.existsByCopyId(a1.getId())).isTrue();
        assertThat(loanRepository.existsByCopyBookId(bookA.getId())).isTrue();
        assertThat(loanRepository.existsByReaderId(r1.reader().getId())).isTrue();
    }

    @Test
    @DisplayName("T01 冒烟：existsOverdueByReaderId 逾期边界（应还日当天不算逾期；已还不算）（支撑 AC-LOAN-06）")
    void overdueBoundary() {
        AppUser admin = testDataFactory.admin("smoke-admin", "Admin@12345");
        ReaderFixture fixture = testDataFactory.reader("smoke-r", "Reader@123", "读者", ReaderStatus.ACTIVE);
        Book book = testDataFactory.book("9787115428028", "书", "作者", null);
        BookCopy c1 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        BookCopy c2 = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        Long readerId = fixture.reader().getId();

        // 应还日 = 今天 → 未逾期
        Loan dueToday = testDataFactory.activeLoan(c1, fixture.reader(), today().minusDays(30), today(), admin.getId());
        assertThat(loanRepository.existsOverdueByReaderId(readerId, today())).isFalse();
        // 应还日 = 昨天 → 逾期
        Loan overdue = testDataFactory.activeLoan(c2, fixture.reader(), today().minusDays(31), today().minusDays(1),
                admin.getId());
        assertThat(loanRepository.existsOverdueByReaderId(readerId, today())).isTrue();
        // 全部归还后 → 未逾期（历史逾期不影响）
        transactionTemplate.executeWithoutResult(tx -> {
            loanRepository.markReturnedIfActive(dueToday.getId(), mutableClock.instant(), today(), admin.getId());
            loanRepository.markReturnedIfActive(overdue.getId(), mutableClock.instant(), today(), admin.getId());
        });
        assertThat(loanRepository.existsOverdueByReaderId(readerId, today())).isFalse();
    }

    @Test
    @DisplayName("T01 冒烟：markReturnedIfActive 只成功一次（并发重复还书的行数语义，支撑 AC-LOAN-04）")
    void markReturnedIfActiveOnlyOnce() {
        AppUser admin = testDataFactory.admin("smoke-admin", "Admin@12345");
        ReaderFixture fixture = testDataFactory.reader("smoke-r", "Reader@123", "读者", ReaderStatus.ACTIVE);
        Book book = testDataFactory.book("9787115428028", "书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        Loan loan = testDataFactory.activeLoan(copy, fixture.reader(), today().minusDays(2), today().plusDays(28),
                admin.getId());
        Instant returnedAt = mutableClock.instant();
        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(loanRepository.markReturnedIfActive(loan.getId(), returnedAt, today(), admin.getId()))
                    .isEqualTo(1);
            assertThat(loanRepository.markReturnedIfActive(loan.getId(), returnedAt, today(), admin.getId()))
                    .isZero();
        });
        Loan reloaded = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(reloaded.getReturnedAt()).isEqualTo(returnedAt);
        assertThat(reloaded.getReturnDate()).isEqualTo(today());
        assertThat(reloaded.getReturnOperatorId()).isEqualTo(admin.getId());
    }

    @Test
    @DisplayName("T01 冒烟：ux_loan_active_copy 部分唯一索引拒绝同副本第二条在借记录（支撑 AC-LOAN-03）")
    void activeCopyUniqueIndex() {
        AppUser admin = testDataFactory.admin("smoke-admin", "Admin@12345");
        ReaderFixture r1 = testDataFactory.reader("smoke-r1", "Reader@123", "读者一", ReaderStatus.ACTIVE);
        ReaderFixture r2 = testDataFactory.reader("smoke-r2", "Reader@123", "读者二", ReaderStatus.ACTIVE);
        Book book = testDataFactory.book("9787115428028", "书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        testDataFactory.activeLoan(copy, r1.reader(), today(), today().plusDays(30), admin.getId());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            Loan second = new Loan(copy, r2.reader(), mutableClock.instant(), today(), today().plusDays(30),
                    admin.getId());
            loanRepository.saveAndFlush(second);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(loanRepository.countActiveByReaderId(r1.reader().getId())).isEqualTo(1);
        assertThat(loanRepository.countActiveByReaderId(r2.reader().getId())).isZero();
    }

    @Test
    @DisplayName("T01 冒烟：Converter 往返（Instant=epoch 毫秒、LocalDate=ISO TEXT）与历史借阅查询")
    void converterRoundTripAndHistory() {
        AppUser admin = testDataFactory.admin("smoke-admin", "Admin@12345");
        ReaderFixture fixture = testDataFactory.reader("smoke-r", "Reader@123", "读者", ReaderStatus.ACTIVE);
        Book book = testDataFactory.book("9787115428028", "书", "作者", null);
        BookCopy copy = testDataFactory.copy(book, CopyStatus.AVAILABLE);
        LocalDate borrowDate = today().minusDays(10);
        LocalDate returnDate = today().minusDays(3);
        Loan history = testDataFactory.returnedLoan(copy, fixture.reader(), borrowDate, borrowDate.plusDays(30),
                returnDate, admin.getId());

        Long epochMillis = jdbcTemplate.queryForObject("SELECT borrowed_at FROM loan WHERE id = ?", Long.class,
                history.getId());
        String borrowText = jdbcTemplate.queryForObject("SELECT borrow_date FROM loan WHERE id = ?", String.class,
                history.getId());
        assertThat(borrowText).isEqualTo(borrowDate.toString());
        assertThat(epochMillis).isEqualTo(history.getBorrowedAt().toEpochMilli());

        Loan reloaded = loanRepository.findById(history.getId()).orElseThrow();
        assertThat(reloaded.getBorrowDate()).isEqualTo(borrowDate);
        assertThat(reloaded.getDueDate()).isEqualTo(borrowDate.plusDays(30));
        assertThat(reloaded.getReturnDate()).isEqualTo(returnDate);
        assertThat(reloaded.getBorrowedAt()).isEqualTo(history.getBorrowedAt());
        // 只有历史记录的副本：findActiveByCopyId 为空
        assertThat(loanRepository.findActiveByCopyId(copy.getId())).isEmpty();
        assertThat(loanRepository.existsByCopyId(copy.getId())).isTrue();
    }
}