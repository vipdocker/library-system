package com.example.library.seed;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.library.common.config.LibraryProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * dev 演示数据（design-v0.1 第 11 节；DOD-06）。
 * 只在 dev profile 且 lib.seed.demo=true 时装配；只在 book 表为空时执行（幂等）。
 * 管理员由 AdminBootstrap 创建（@Order(1) 先于本类）。
 * 所有演示口令只用于本地开发。
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "lib.seed.demo", havingValue = "true")
@Order(2)
public class DemoDataLoader implements ApplicationRunner {

    /** 演示书目（ISBN 全部通过校验位检查，见 seed/DemoDataIsbnTest）。 */
    public record DemoBook(String isbn, String title, String author, String publisher, int publishYear,
                           int categoryIndex, int copies) {
    }

    public static final String[] CATEGORY_NAMES = {"文学", "计算机", "历史", "科学", "艺术"};

    public static final String DEMO_READER_PASSWORD = "Reader@123";

    public static final List<DemoBook> DEMO_BOOKS = List.of(
            new DemoBook("9787115428028", "Python编程从入门到实践", "Eric Matthes", "人民邮电出版社", 2016, 1, 1),
            new DemoBook("9787115544834", "深入理解计算机系统", "Randal E. Bryant", "机械工业出版社", 2016, 1, 2),
            new DemoBook("9787302423218", "算法导论", "Thomas H. Cormen", "机械工业出版社", 2013, 1, 3),
            new DemoBook("9787111544937", "活着", "余华", "作家出版社", 2012, 0, 1),
            new DemoBook("9787020024759", "百年孤独", "加西亚·马尔克斯", "人民文学出版社", 1994, 0, 2),
            new DemoBook("9787544270069", "围城", "钱锺书", "人民文学出版社", 1991, 0, 3),
            new DemoBook("9787532776788", "万历十五年", "黄仁宇", "上海译文出版社", 2007, 2, 1),
            new DemoBook("9787108009821", "人类简史", "尤瓦尔·赫拉利", "中信出版社", 2014, 2, 2),
            new DemoBook("9787506380584", "时间简史", "史蒂芬·霍金", "湖南科学技术出版社", 2010, 3, 3),
            new DemoBook("9787208061644", "物种起源", "查尔斯·达尔文", "上海人民出版社", 2005, 3, 1),
            new DemoBook("9787559612342", "艺术的故事", "贡布里希", "广西美术出版社", 2008, 4, 2),
            new DemoBook("9787513328906", "中国美术史", "洪再新", "中国美术学院出版社", 2000, 4, 3));

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final LibraryProperties properties;
    private final AppUserRepository appUserRepository;
    private final ReaderRepository readerRepository;
    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
    private final LoanRepository loanRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DemoDataLoader(LibraryProperties properties, AppUserRepository appUserRepository,
                          ReaderRepository readerRepository, CategoryRepository categoryRepository,
                          BookRepository bookRepository, BookCopyRepository bookCopyRepository,
                          LoanRepository loanRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.properties = properties;
        this.appUserRepository = appUserRepository;
        this.readerRepository = readerRepository;
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.bookCopyRepository = bookCopyRepository;
        this.loanRepository = loanRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    /** 幂等入口：book 表非空时不做任何事。测试可显式调用。 */
    @Transactional
    public void seedIfEmpty() {
        if (bookRepository.count() > 0) {
            return;
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        AppUser admin = appUserRepository.findByUsernameIgnoreCase(properties.getAdmin().getUsername())
                .orElseThrow(() -> new IllegalStateException("演示数据需要管理员账号（AdminBootstrap 应先执行）"));

        List<Category> categories = new ArrayList<>();
        for (String name : CATEGORY_NAMES) {
            categories.add(categoryRepository.save(new Category(name, "演示分类：" + name, now)));
        }

        List<BookCopy> firstCopies = new ArrayList<>();
        for (DemoBook demo : DEMO_BOOKS) {
            Book book = new Book(demo.isbn(), demo.title(), demo.author(),
                    categories.get(demo.categoryIndex()).getId(), now);
            book.setPublisher(demo.publisher());
            book.setPublishYear(demo.publishYear());
            book = bookRepository.save(book);
            BookCopy first = null;
            for (int i = 0; i < demo.copies(); i++) {
                BookCopy copy = createCopy(book, now);
                if (first == null) {
                    first = copy;
                }
            }
            firstCopies.add(first);
        }

        Reader reader1 = createReader("reader1", "演示读者一", ReaderStatus.ACTIVE, now);
        Reader reader2 = createReader("reader2", "演示读者二", ReaderStatus.ACTIVE, now);
        createReader("reader3", "演示读者三", ReaderStatus.DISABLED, now);

        // reader1：1 条在借（借于 today-5，应还 today+25）
        createActiveLoan(firstCopies.get(3), reader1, admin, today.minusDays(5), today.plusDays(25), now);
        // reader2：1 条逾期（借于 today-40，应还 today-10）
        createActiveLoan(firstCopies.get(0), reader2, admin, today.minusDays(40), today.minusDays(10), now);

        // 历史借阅：最近 60 天内分布的已归还记录（副本保持 AVAILABLE）
        for (int i = 0; i < 8; i++) {
            LocalDate borrowDate = today.minusDays(55L - i * 6L);
            LocalDate returnDate = borrowDate.plusDays(7);
            BookCopy copy = firstCopies.get(4 + (i % 8));
            Loan loan = new Loan(copy, i % 2 == 0 ? reader1 : reader2,
                    atTen(borrowDate), borrowDate, borrowDate.plusDays(30), admin.getId());
            loan.setReturnedAt(atTen(returnDate));
            loan.setReturnDate(returnDate);
            loan.setReturnOperatorId(admin.getId());
            loanRepository.save(loan);
        }

        log.info("seed.demo.loaded books={} copies={} readers=3", bookRepository.count(), bookCopyRepository.count());
    }

    private BookCopy createCopy(Book book, Instant now) {
        BookCopy copy = new BookCopy(book, "PENDING-" + UUID.randomUUID(), CopyStatus.AVAILABLE, now);
        copy = bookCopyRepository.saveAndFlush(copy);
        copy.setBarcode(String.format("C%08d", copy.getId()));
        return bookCopyRepository.save(copy);
    }

    private Reader createReader(String username, String name, ReaderStatus status, Instant now) {
        AppUser user = new AppUser(username, passwordEncoder.encode(DEMO_READER_PASSWORD), Role.READER,
                status == ReaderStatus.ACTIVE, now);
        user = appUserRepository.save(user);
        Reader reader = new Reader(user.getId(), "PENDING-" + UUID.randomUUID(), name, status, now);
        reader = readerRepository.saveAndFlush(reader);
        reader.setCardNo(String.format("R%06d", reader.getId()));
        return readerRepository.save(reader);
    }

    private void createActiveLoan(BookCopy copy, Reader reader, AppUser operator,
                                  LocalDate borrowDate, LocalDate dueDate, Instant now) {
        Loan loan = new Loan(copy, reader, atTen(borrowDate), borrowDate, dueDate, operator.getId());
        loanRepository.save(loan);
        int updated = bookCopyRepository.markBorrowedIfAvailable(copy.getId(), now);
        if (updated != 1) {
            throw new IllegalStateException("演示数据副本占用失败 copyId=" + copy.getId());
        }
    }

    private Instant atTen(LocalDate date) {
        return date.atTime(10, 0).atZone(clock.getZone()).toInstant();
    }
}