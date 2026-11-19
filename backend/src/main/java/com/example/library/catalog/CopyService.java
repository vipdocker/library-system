package com.example.library.catalog;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.library.catalog.dto.CopyResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.LoanRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 副本服务（design-v0.1 第 8.4 节副本接口，AC-COPY-01/02）。
 * - 条码系统生成 C%08d(id)：先写 PENDING-UUID，拿到自增 id 后同一事务内回填（第 6 节）；
 * - 状态只允许在 AVAILABLE/UNAVAILABLE 之间切换；BORROWED 只能由借还流程设置（第 6 节枚举注释）；
 * - 在借副本改状态 → 409 COPY_ON_LOAN；删除在借副本 → 409 COPY_ON_LOAN；
 *   删除有借阅历史的副本 → 409 COPY_HAS_LOAN_HISTORY（外键 RESTRICT 兜底）。
 */
@Service
public class CopyService {

    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
    private final LoanRepository loanRepository;
    private final Clock clock;

    public CopyService(BookRepository bookRepository, BookCopyRepository bookCopyRepository,
                       LoanRepository loanRepository, Clock clock) {
        this.bookRepository = bookRepository;
        this.bookCopyRepository = bookCopyRepository;
        this.loanRepository = loanRepository;
        this.clock = clock;
    }

    /** GET /api/books/{id}/copies（ADMIN，URL 规则保证）。 */
    @Transactional(readOnly = true)
    public List<CopyResponse> listForBook(Long bookId) {
        requireBook(bookId);
        return bookCopyRepository.findByBookIdOrderByIdAsc(bookId).stream()
                .map(CopyResponse::from)
                .toList();
    }

    /** POST /api/books/{id}/copies：追加 1..50 个副本，返回新建的副本。 */
    @Transactional
    public List<CopyResponse> addCopies(Long bookId, int count) {
        Book book = requireBook(bookId);
        return createCopies(book, count).stream()
                .map(CopyResponse::from)
                .toList();
    }

    /**
     * 创建副本并回填条码（同一事务）。BookService 创建图书的初始副本时复用本方法，
     * 加入调用方事务，任何失败整体回滚。
     */
    @Transactional
    public List<BookCopy> createCopies(Book book, int count) {
        if (count <= 0) {
            return List.of();
        }
        var now = clock.instant();
        List<BookCopy> copies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            copies.add(new BookCopy(book, "PENDING-" + UUID.randomUUID(), CopyStatus.AVAILABLE, now));
        }
        copies = bookCopyRepository.saveAllAndFlush(copies);
        for (BookCopy copy : copies) {
            copy.setBarcode(String.format("C%08d", copy.getId()));
        }
        return bookCopyRepository.saveAllAndFlush(copies);
    }

    /** PATCH /api/copies/{id}：只允许 AVAILABLE / UNAVAILABLE。 */
    @Transactional
    public CopyResponse updateStatus(Long copyId, String status) {
        CopyStatus target = parseAllowedStatus(status);
        BookCopy copy = bookCopyRepository.findById(copyId)
                .orElseThrow(() -> ApiException.of(ErrorCode.COPY_NOT_FOUND));
        if (copy.getStatus() == CopyStatus.BORROWED) {
            throw ApiException.of(ErrorCode.COPY_ON_LOAN);
        }
        copy.setStatus(target);
        copy.setUpdatedAt(clock.instant());
        return CopyResponse.from(bookCopyRepository.saveAndFlush(copy));
    }

    /** DELETE /api/copies/{id}：先查在借，再查历史。 */
    @Transactional
    public void delete(Long copyId) {
        BookCopy copy = bookCopyRepository.findById(copyId)
                .orElseThrow(() -> ApiException.of(ErrorCode.COPY_NOT_FOUND));
        if (loanRepository.findActiveByCopyId(copyId).isPresent()) {
            throw ApiException.of(ErrorCode.COPY_ON_LOAN);
        }
        if (loanRepository.existsByCopyId(copyId)) {
            throw ApiException.of(ErrorCode.COPY_HAS_LOAN_HISTORY);
        }
        try {
            bookCopyRepository.delete(copy);
            bookCopyRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 外键 RESTRICT 兜底（第 9.5 节）
            throw ApiException.of(ErrorCode.COPY_HAS_LOAN_HISTORY);
        }
    }

    private Book requireBook(Long bookId) {
        return bookRepository.findById(bookId)
                .orElseThrow(() -> ApiException.of(ErrorCode.BOOK_NOT_FOUND));
    }

    private static CopyStatus parseAllowedStatus(String status) {
        if ("AVAILABLE".equals(status)) {
            return CopyStatus.AVAILABLE;
        }
        if ("UNAVAILABLE".equals(status)) {
            return CopyStatus.UNAVAILABLE;
        }
        // BORROWED 或其他非法值 → 400（第 8.4 节：传入 BORROWED 时 400）
        throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                Map.of(), List.of(new ErrorResponse.FieldErrorItem("status", "status 只能为 AVAILABLE 或 UNAVAILABLE")));
    }
}