package com.example.library.catalog;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.library.catalog.dto.BookCreateRequest;
import com.example.library.catalog.dto.BookDetailResponse;
import com.example.library.catalog.dto.BookSummaryResponse;
import com.example.library.catalog.dto.BookUpdateRequest;
import com.example.library.catalog.dto.CategoryRef;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.util.IsbnUtils;
import com.example.library.common.util.LikeEscaper;
import com.example.library.common.util.Texts;
import com.example.library.common.web.PageRequestParams;
import com.example.library.common.web.PageResponse;
import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.Category;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.BookCopyRepository.CopyCounts;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.CategoryRepository;
import com.example.library.domain.repository.LoanRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 图书服务（design-v0.1 第 8.4 节图书接口与检索语义，AC-BOOK-01/02/03、AC-SRCH-01）。
 * - ISBN：创建/更新做严格校验（D4③，IsbnUtils.isValid），规范化后存储，ISBN-10 不转 13 位；
 *   检索只做规范化（长度不符 → 空结果，不报错）；
 * - 删除前检查借阅历史：在借 → BOOK_HAS_ACTIVE_LOANS；有历史 → BOOK_HAS_LOAN_HISTORY；
 *   无历史时同一事务内删除全部副本和图书（外键 RESTRICT 兜底）；
 * - totalCopies/availableCopies 为派生值，读取时聚合，不维护冗余计数（第 6 节）。
 */
@Service
public class BookService {

    private static final Set<String> SORT_WHITELIST = Set.of("title", "author", "publishYear", "createdAt");
    private static final Set<String> SEARCH_FIELDS = Set.of("all", "title", "author", "isbn");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "title");
    private static final int MAX_Q_LENGTH = 100;

    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
    private final CategoryRepository categoryRepository;
    private final LoanRepository loanRepository;
    private final CopyService copyService;
    private final Clock clock;
    private final BookCoverRepository covers;

    public BookService(BookRepository bookRepository, BookCopyRepository bookCopyRepository,
                       CategoryRepository categoryRepository, LoanRepository loanRepository,
                       CopyService copyService, Clock clock, BookCoverRepository covers) {
        this.bookRepository = bookRepository;
        this.bookCopyRepository = bookCopyRepository;
        this.categoryRepository = categoryRepository;
        this.loanRepository = loanRepository;
        this.copyService = copyService;
        this.clock = clock;
        this.covers = covers;
    }

    /** GET /api/books：检索 + 过滤 + 分页 + 白名单排序。 */
    @Transactional(readOnly = true)
    public PageResponse<BookSummaryResponse> search(String q, String field, Long categoryId, Boolean availableOnly,
                                                    Integer page, Integer size, String sort) {
        String query = Texts.trimToNull(q);
        if (query != null && query.length() > MAX_Q_LENGTH) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "q 长度不能超过 100");
        }
        String searchField = Texts.trimToNull(field) == null ? "all" : field.trim();
        if (!SEARCH_FIELDS.contains(searchField)) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "field 只能为 all、title、author 或 isbn");
        }
        Pageable pageable = PageRequestParams.toPageable(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);

        boolean matchAll = query == null;
        String normalizedIsbn = query == null ? null : IsbnUtils.normalize(query);
        boolean matchTitle = !matchAll && (searchField.equals("title") || searchField.equals("all"));
        boolean matchAuthor = !matchAll && (searchField.equals("author") || searchField.equals("all"));
        boolean matchIsbn = !matchAll && (searchField.equals("isbn") || searchField.equals("all"))
                && normalizedIsbn != null;
        if (!matchAll && !matchTitle && !matchAuthor && !matchIsbn) {
            // field=isbn 且规范化后长度不符：空结果，不报错（第 8.4 节检索语义）
            return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
        }
        String pattern = "%" + LikeEscaper.escape(query == null ? "" : query) + "%";
        Page<Book> result = bookRepository.searchBooks(
                categoryId != null, categoryId == null ? 0L : categoryId,
                Boolean.TRUE.equals(availableOnly),
                matchAll, matchTitle, matchAuthor, matchIsbn,
                pattern, normalizedIsbn == null ? "" : normalizedIsbn, pageable);
        BatchContext context = batchContext(result.getContent());
        return PageResponse.from(result, book -> toSummary(book, context));
    }

    /** GET /api/books/{id}。 */
    @Transactional(readOnly = true)
    public BookDetailResponse detail(Long id) {
        Book book = requireBook(id);
        return toDetail(book);
    }

    /** POST /api/books：校验顺序 400（字段/ISBN）→ 404（分类）→ 409（ISBN 重复）。 */
    @Transactional
    public BookDetailResponse create(BookCreateRequest request, BookWriteService.PreparedCover cover,
                                     BookWriteService.ResolvedSource source) {
        String isbn = normalizeAndValidateIsbn(request.isbn());
        Category category = resolveCategory(request.categoryId());
        if (bookRepository.existsByIsbn(isbn)) {
            throw ApiException.of(ErrorCode.ISBN_DUPLICATE);
        }
        var now = clock.instant();
        Book book = new Book(isbn, request.title(), request.author(),
                category == null ? null : category.getId(), now);
        applyOptionalFields(book, request.publisher(), request.publishYear(), request.description());
        book = saveConflictSafe(book);
        int initialCopies = request.initialCopies() == null ? 1 : request.initialCopies();
        copyService.createCopies(book, initialCopies);
        if (source != null) {
            applyMetadataSource(book, source);
        }
        bookRepository.flush();
        applyCover(book.getId(), cover);
        return toDetail(book);
    }

    /** PUT /api/books/{id}：不含 initialCopies。 */
    @Transactional
    public BookDetailResponse update(Long id, BookUpdateRequest request,
                                     BookWriteService.PreparedCover cover, BookWriteService.ResolvedSource source) {
        String isbn = normalizeAndValidateIsbn(request.isbn());
        Book book = requireBook(id);
        Category category = resolveCategory(request.categoryId());
        if (bookRepository.existsByIsbnAndIdNot(isbn, id)) {
            throw ApiException.of(ErrorCode.ISBN_DUPLICATE);
        }
        book.setIsbn(isbn);
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setCategoryId(category == null ? null : category.getId());
        applyOptionalFields(book, request.publisher(), request.publishYear(), request.description());
        book.setUpdatedAt(clock.instant());
        if (source != null) {
            applyMetadataSource(book, source);
        }
        book = saveConflictSafe(book);
        applyCover(id, cover);
        return toDetail(book);
    }

    /** DELETE /api/books/{id}：先查在借，再查历史；无历史时连同副本一起删除。 */
    @Transactional
    public void delete(Long id) {
        Book book = requireBook(id);
        if (loanRepository.existsActiveByCopyBookId(id)) {
            throw ApiException.of(ErrorCode.BOOK_HAS_ACTIVE_LOANS);
        }
        if (loanRepository.existsByCopyBookId(id)) {
            throw ApiException.of(ErrorCode.BOOK_HAS_LOAN_HISTORY);
        }
        try {
            bookCopyRepository.deleteByBookId(id);
            bookRepository.delete(book);
            bookRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 外键 RESTRICT 兜底（第 9.5 节）：仍有借阅引用时转成对应 409
            throw ApiException.of(ErrorCode.BOOK_HAS_LOAN_HISTORY);
        }
    }

    // ---- 内部辅助 ----

    private void applyCover(Long id, BookWriteService.PreparedCover cover) {
        if ("REPLACE".equals(cover.action())) {
            covers.replace(id, cover.image(), cover.sourceIsbn(), cover.sourceProvider(), clock.millis());
        } else if ("REMOVE".equals(cover.action())) {
            covers.remove(id);
        }
    }

    /** SHOWAPI application clears the legacy OL isbn; OPEN_LIBRARY keeps both representations in sync. */
    private static void applyMetadataSource(Book book, BookWriteService.ResolvedSource source) {
        book.setSourceProvider(source.provider());
        book.setSourceIsbn(source.isbn());
        book.setOpenLibraryIsbn("OPEN_LIBRARY".equals(source.provider()) ? source.isbn() : null);
    }

    private Book requireBook(Long id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.BOOK_NOT_FOUND));
    }

    private Category resolveCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> ApiException.of(ErrorCode.CATEGORY_NOT_FOUND));
    }

    /** D4③：规范化 + 严格校验位检查；失败 → 400 + fieldErrors[isbn]。 */
    private static String normalizeAndValidateIsbn(String raw) {
        String normalized = IsbnUtils.normalize(raw);
        if (normalized == null || !IsbnUtils.isValid(raw)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                    Map.of(), List.of(new ErrorResponse.FieldErrorItem("isbn", "ISBN 格式或校验位错误")));
        }
        return normalized;
    }

    private static void applyOptionalFields(Book book, String publisher, Integer publishYear, String description) {
        book.setPublisher(publisher);
        book.setPublishYear(publishYear);
        book.setDescription(description);
    }

    private Book saveConflictSafe(Book book) {
        try {
            return bookRepository.saveAndFlush(book);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.of(ErrorCode.ISBN_DUPLICATE);
        }
    }

    private BookDetailResponse toDetail(Book book) {
        long total = bookCopyRepository.countByBookId(book.getId());
        long available = bookCopyRepository.countByBookIdAndStatus(book.getId(), CopyStatus.AVAILABLE);
        return BookDetailResponse.from(book, categoryRef(book.getCategoryId()), total, available,
                covers.metadata(List.of(book.getId())).get(book.getId()));
    }

    private CategoryRef categoryRef(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .map(c -> new CategoryRef(c.getId(), c.getName()))
                .orElse(null);
    }

    /** 批量上下文：一次聚合副本计数 + 一次取分类，避免 N+1。 */
    private record BatchContext(Map<Long, CopyCounts> counts, Map<Long, Category> categories,
                                Map<Long, com.example.library.catalog.dto.BookCoverResponse> covers) {
    }

    private BatchContext batchContext(List<Book> books) {
        List<Long> bookIds = books.stream().map(Book::getId).toList();
        Map<Long, CopyCounts> counts = bookIds.isEmpty() ? Map.of()
                : bookCopyRepository.countCopiesByBookIds(bookIds).stream()
                        .collect(Collectors.toMap(CopyCounts::getBookId, Function.identity()));
        Set<Long> categoryIds = books.stream().map(Book::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        Map<Long, Category> categories = categoryIds.isEmpty() ? Map.of()
                : categoryRepository.findAllById(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));
        return new BatchContext(counts, categories, covers.metadata(bookIds));
    }

    private BookSummaryResponse toSummary(Book book, BatchContext context) {
        CopyCounts count = context.counts().get(book.getId());
        long total = count == null ? 0 : count.getTotalCopies();
        long available = count == null ? 0 : count.getAvailableCopies();
        Category category = book.getCategoryId() == null ? null : context.categories().get(book.getCategoryId());
        CategoryRef ref = category == null ? null : new CategoryRef(category.getId(), category.getName());
        return BookSummaryResponse.from(book, ref, total, available, context.covers().get(book.getId()));
    }
}