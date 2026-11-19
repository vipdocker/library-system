package com.example.library.support;

import java.time.LocalDate;
import java.util.UUID;

import com.example.library.domain.entity.AppUser;
import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Category;
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
import com.example.library.security.LibraryUserDetailsService;
import com.example.library.security.LibraryUserPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 测试数据工厂（测试支撑，共享契约：T01 之后只读；各任务私有夹具写在各自测试包）。
 * 生成码与生产规则一致：cardNo=R%06d(id)、barcode=C%08d(id)（先 PENDING-UUID 再回填）。
 * 时间戳取注入的 MutableClock。
 */
public class TestDataFactory {

    /** 读者账号 + 档案的组合夹具。 */
    public record ReaderFixture(AppUser user, Reader reader) {
    }

    private final AppUserRepository appUserRepository;
    private final ReaderRepository readerRepository;
    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
    private final LoanRepository loanRepository;
    private final PasswordEncoder passwordEncoder;
    private final LibraryUserDetailsService userDetailsService;
    private final MutableClock clock;

    public TestDataFactory(AppUserRepository appUserRepository, ReaderRepository readerRepository,
                           CategoryRepository categoryRepository, BookRepository bookRepository,
                           BookCopyRepository bookCopyRepository, LoanRepository loanRepository,
                           PasswordEncoder passwordEncoder, LibraryUserDetailsService userDetailsService,
                           MutableClock clock) {
        this.appUserRepository = appUserRepository;
        this.readerRepository = readerRepository;
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.bookCopyRepository = bookCopyRepository;
        this.loanRepository = loanRepository;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
        this.clock = clock;
    }

    public AppUser admin(String username, String rawPassword) {
        return user(username, rawPassword, Role.ADMIN, true);
    }

    public AppUser user(String username, String rawPassword, Role role, boolean enabled) {
        return appUserRepository.save(new AppUser(username, passwordEncoder.encode(rawPassword), role, enabled,
                clock.instant()));
    }

    public ReaderFixture reader(String username, String rawPassword, String name, ReaderStatus status) {
        AppUser user = user(username, rawPassword, Role.READER, status == ReaderStatus.ACTIVE);
        Reader reader = new Reader(user.getId(), "PENDING-" + UUID.randomUUID(), name, status, clock.instant());
        reader = readerRepository.saveAndFlush(reader);
        reader.setCardNo(String.format("R%06d", reader.getId()));
        reader = readerRepository.save(reader);
        return new ReaderFixture(user, reader);
    }

    public Category category(String name) {
        return categoryRepository.save(new Category(name, null, clock.instant()));
    }

    public Book book(String isbn, String title, String author, Long categoryId) {
        return bookRepository.save(new Book(isbn, title, author, categoryId, clock.instant()));
    }

    public BookCopy copy(Book book, CopyStatus status) {
        BookCopy copy = new BookCopy(book, "PENDING-" + UUID.randomUUID(), status, clock.instant());
        copy = bookCopyRepository.saveAndFlush(copy);
        copy.setBarcode(String.format("C%08d", copy.getId()));
        return bookCopyRepository.save(copy);
    }

    /** 在借借阅；同时把副本置为 BORROWED（与生产借书流程一致的条件 UPDATE，需事务）。 */
    @org.springframework.transaction.annotation.Transactional
    public Loan activeLoan(BookCopy copy, Reader reader, LocalDate borrowDate, LocalDate dueDate, Long operatorId) {
        Loan loan = new Loan(copy, reader, borrowDate.atTime(10, 0).atZone(clock.getZone()).toInstant(),
                borrowDate, dueDate, operatorId);
        loan = loanRepository.save(loan);
        int updated = bookCopyRepository.markBorrowedIfAvailable(copy.getId(), clock.instant());
        if (updated != 1) {
            throw new IllegalStateException("夹具副本占用失败 copyId=" + copy.getId());
        }
        return loan;
    }

    /** 已归还的历史借阅；副本保持传入状态（通常 AVAILABLE）。 */
    public Loan returnedLoan(BookCopy copy, Reader reader, LocalDate borrowDate, LocalDate dueDate,
                             LocalDate returnDate, Long operatorId) {
        Loan loan = new Loan(copy, reader, borrowDate.atTime(10, 0).atZone(clock.getZone()).toInstant(),
                borrowDate, dueDate, operatorId);
        loan.setReturnedAt(returnDate.atTime(10, 0).atZone(clock.getZone()).toInstant());
        loan.setReturnDate(returnDate);
        loan.setReturnOperatorId(operatorId);
        return loanRepository.save(loan);
    }

    /** 从数据库加载真实 principal（供 SecurityMockMvcRequestPostProcessors.user(...) 使用）。 */
    public LibraryUserPrincipal principal(String username) {
        return userDetailsService.loadUserByUsername(username);
    }
}