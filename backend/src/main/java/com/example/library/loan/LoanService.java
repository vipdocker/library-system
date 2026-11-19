package com.example.library.loan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.library.common.config.LibraryProperties;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.util.LikeEscaper;
import com.example.library.common.util.Texts;
import com.example.library.common.web.PageRequestParams;
import com.example.library.common.web.PageResponse;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.ReaderRepository;
import com.example.library.domain.repository.ReaderRepository.ReaderLoanCounts;
import com.example.library.loan.dto.LoanResponse;
import com.example.library.security.LibraryUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 借阅服务（design-v0.1 第 9 节；AC-LOAN-01..08、AC-AUTH-05、AC-RDR-02 借阅侧）。
 * - borrow：第 9.2 节校验顺序（命中第一条即返回）+ 条件 UPDATE 原子占用 + ux_loan_active_copy 兜底；
 * - return：第 9.3 节原子关闭；副本状态不一致只 WARN 不阻断；
 * - renew：第 9.4 节校验顺序 + 乐观条件 UPDATE；读者归属校验防 IDOR（第 7 节）；
 * - 并发正确性：Hikari 单连接串行化 + 条件 UPDATE + 部分唯一索引（第 9.5 节）；禁止 REQUIRES_NEW；
 * - 事件日志 INFO：loanId/readerId/copyId/operatorId（第 12 节），不输出口令或 Token。
 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    /** 第 8.4 节借阅排序白名单；默认 borrowDate,desc（同日按 id desc 保证分页确定性）。 */
    private static final Set<String> SORT_WHITELIST = Set.of("borrowDate", "dueDate");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "borrowDate")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final ReaderRepository readerRepository;
    private final BookCopyRepository bookCopyRepository;
    private final LoanRepository loanRepository;
    private final LibraryProperties properties;
    private final LoanPolicy policy;
    private final LoanViewMapper viewMapper;
    private final Clock clock;

    public LoanService(ReaderRepository readerRepository, BookCopyRepository bookCopyRepository,
                       LoanRepository loanRepository, LibraryProperties properties,
                       LoanPolicy policy, LoanViewMapper viewMapper, Clock clock) {
        this.readerRepository = readerRepository;
        this.bookCopyRepository = bookCopyRepository;
        this.loanRepository = loanRepository;
        this.properties = properties;
        this.policy = policy;
        this.viewMapper = viewMapper;
        this.clock = clock;
    }

    /** POST /api/loans（第 9.2 节，单事务；任何一步失败整体回滚）。 */
    @Transactional
    public LoanResponse borrow(String readerCardNo, String copyBarcode, Long operatorId) {
        LocalDate today = policy.today();
        // 1. 按卡号找读者
        Reader reader = readerRepository.findByCardNo(readerCardNo)
                .orElseThrow(() -> ApiException.of(ErrorCode.READER_NOT_FOUND));
        // 2. 停用读者
        if (reader.getStatus() == ReaderStatus.DISABLED) {
            throw ApiException.of(ErrorCode.READER_DISABLED);
        }
        // 3. 有逾期未还
        if (loanRepository.existsOverdueByReaderId(reader.getId(), today)) {
            throw ApiException.of(ErrorCode.READER_HAS_OVERDUE);
        }
        // 4. 在借数上限
        long active = loanRepository.countActiveByReaderId(reader.getId());
        int limit = properties.getLoan().getMaxActiveLoans();
        if (active >= limit) {
            throw ApiException.withDetails(ErrorCode.LOAN_LIMIT_EXCEEDED, Map.of("limit", limit, "current", active));
        }
        // 5. 按条码找副本
        BookCopy copy = bookCopyRepository.findByBarcode(copyBarcode)
                .orElseThrow(() -> ApiException.of(ErrorCode.COPY_NOT_FOUND));
        // 6. 副本状态
        if (copy.getStatus() != CopyStatus.AVAILABLE) {
            throw ApiException.of(ErrorCode.COPY_NOT_AVAILABLE);
        }
        // 7. D4②：同一读者不得同时借同一书目的多个副本
        if (loanRepository.existsActiveByReaderIdAndBookId(reader.getId(), copy.getBook().getId())) {
            throw ApiException.of(ErrorCode.DUPLICATE_BOOK_LOAN);
        }
        // 8. 原子占用（条件 UPDATE；行数 != 1 即竞争失败）
        Instant now = clock.instant();
        if (bookCopyRepository.markBorrowedIfAvailable(copy.getId(), now) != 1) {
            throw ApiException.of(ErrorCode.COPY_NOT_AVAILABLE);
        }
        // 条件 UPDATE 会清空持久化上下文：重新加载，保证关联处于托管状态
        Reader managedReader = readerRepository.findById(reader.getId())
                .orElseThrow(() -> ApiException.of(ErrorCode.READER_NOT_FOUND));
        BookCopy managedCopy = bookCopyRepository.findById(copy.getId())
                .orElseThrow(() -> ApiException.of(ErrorCode.COPY_NOT_FOUND));
        // 9. 插入 loan；ux_loan_active_copy 唯一索引兜底并发借出（第 9.5 节第二层）
        Loan loan = new Loan(managedCopy, managedReader, now, today, policy.dueDateFor(today), operatorId);
        try {
            loan = loanRepository.saveAndFlush(loan);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.of(ErrorCode.COPY_NOT_AVAILABLE);
        }
        // 10. INFO 事件日志
        log.info("loan.borrow loanId={} readerId={} copyId={} operatorId={}",
                loan.getId(), reader.getId(), copy.getId(), operatorId);
        return viewMapper.toResponse(loan, LoanViewMapper.Actor.admin(), today,
                overdueCount(reader.getId(), today));
    }

    /** POST /api/loans/{id}/return（第 9.3 节）。 */
    @Transactional
    public LoanResponse returnLoan(Long loanId, Long operatorId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
        if (loan.getReturnedAt() != null) {
            throw ApiException.of(ErrorCode.LOAN_ALREADY_RETURNED);
        }
        Long copyId = loan.getCopy().getId();
        Long readerId = loan.getReader().getId();
        LocalDate today = policy.today();
        Instant now = clock.instant();
        // 原子关闭：行数为 0 对应并发重复还书
        if (loanRepository.markReturnedIfActive(loanId, now, today, operatorId) == 0) {
            throw ApiException.of(ErrorCode.LOAN_ALREADY_RETURNED);
        }
        // 释放副本：行数为 0 说明数据不一致，WARN 但不阻断还书
        if (bookCopyRepository.markAvailableIfBorrowed(copyId, now) == 0) {
            log.warn("loan.return.copy-state-mismatch loanId={} copyId={}", loanId, copyId);
        }
        Loan updated = loanRepository.findById(loanId)
                .orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
        log.info("loan.return loanId={} readerId={} copyId={} operatorId={}", loanId, readerId, copyId, operatorId);
        return viewMapper.toResponse(updated, LoanViewMapper.Actor.admin(), today, overdueCount(readerId, today));
    }

    /** POST /api/loans/return-by-barcode（第 8.4 节；无在借记录 → ACTIVE_LOAN_NOT_FOUND）。 */
    @Transactional
    public LoanResponse returnByBarcode(String copyBarcode, Long operatorId) {
        BookCopy copy = bookCopyRepository.findByBarcode(copyBarcode)
                .orElseThrow(() -> ApiException.of(ErrorCode.COPY_NOT_FOUND));
        Loan loan = loanRepository.findActiveByCopyId(copy.getId())
                .orElseThrow(() -> ApiException.of(ErrorCode.ACTIVE_LOAN_NOT_FOUND));
        return returnLoan(loan.getId(), operatorId);
    }

    /** POST /api/loans/{id}/renew（第 9.4 节；D2 的 URL 授权在 SecurityConfig，服务层再做归属校验）。 */
    @Transactional
    public LoanResponse renew(Long loanId, LibraryUserPrincipal actor) {
        LocalDate today = policy.today();
        // 1. 借阅存在；读者只能续借本人借阅（否则 404，不泄露存在性，第 7 节防 IDOR）
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
        if (actor.getRole() == Role.READER) {
            if (!properties.getLoan().isReaderSelfRenew()) {
                // 防御性兜底：正常情况下 URL 规则已返回 403
                throw ApiException.of(ErrorCode.FORBIDDEN);
            }
            if (!actor.getUserId().equals(loan.getReader().getUserId())) {
                throw ApiException.of(ErrorCode.LOAN_NOT_FOUND);
            }
        }
        // 2. 未归还
        if (loan.getReturnedAt() != null) {
            throw ApiException.of(ErrorCode.LOAN_ALREADY_RETURNED);
        }
        // 3. 读者 ACTIVE
        Reader reader = loan.getReader();
        if (reader.getStatus() != ReaderStatus.ACTIVE) {
            throw ApiException.of(ErrorCode.READER_DISABLED);
        }
        // 4. 本条未逾期
        if (policy.isOverdue(loan, today)) {
            throw ApiException.of(ErrorCode.LOAN_OVERDUE);
        }
        // 5. 没有其他逾期（本条未逾期，故存在逾期即“其他”）
        if (loanRepository.existsOverdueByReaderId(reader.getId(), today)) {
            throw ApiException.of(ErrorCode.READER_HAS_OVERDUE);
        }
        // 6. 续借次数
        if (loan.getRenewCount() >= policy.maxRenewals()) {
            throw ApiException.of(ErrorCode.RENEW_LIMIT_REACHED);
        }
        // 乐观条件 UPDATE：防并发重复续借；行数为 0 时重新读取并返回对应错误码
        LocalDate newDueDate = policy.renewedDueDate(loan.getDueDate());
        int updated = loanRepository.renewIfEligible(loanId, newDueDate, loan.getRenewCount(), actor.getUserId());
        if (updated == 0) {
            Loan reread = loanRepository.findById(loanId)
                    .orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
            if (reread.getReturnedAt() != null) {
                throw ApiException.of(ErrorCode.LOAN_ALREADY_RETURNED);
            }
            if (policy.isOverdue(reread, today)) {
                throw ApiException.of(ErrorCode.LOAN_OVERDUE);
            }
            if (reread.getRenewCount() >= policy.maxRenewals()) {
                throw ApiException.of(ErrorCode.RENEW_LIMIT_REACHED);
            }
            throw new IllegalStateException("续借条件更新未命中且无法解释 loanId=" + loanId);
        }
        Loan refreshed = loanRepository.findById(loanId)
                .orElseThrow(() -> ApiException.of(ErrorCode.LOAN_NOT_FOUND));
        log.info("loan.renew loanId={} readerId={} copyId={} operatorId={}",
                loanId, reader.getId(), refreshed.getCopy().getId(), actor.getUserId());
        return viewMapper.toResponse(refreshed, LoanViewMapper.Actor.of(actor), today,
                overdueCount(reader.getId(), today));
    }

    /** GET /api/loans（ADMIN，URL 规则保证；第 8.4 节过滤 + 分页 + 白名单排序）。 */
    @Transactional(readOnly = true)
    public PageResponse<LoanResponse> search(Long readerId, String status, String copyBarcode, String q,
                                             Integer page, Integer size, String sort) {
        String statusFilter = parseStatusFilter(status);
        Pageable pageable = PageRequestParams.toPageable(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        String query = Texts.trimToNull(q);
        String barcode = Texts.trimToNull(copyBarcode);
        String pattern = "%" + LikeEscaper.escape(query == null ? "" : query) + "%";
        LocalDate today = policy.today();
        Page<Loan> result = loanRepository.searchLoans(
                readerId != null, readerId == null ? 0L : readerId,
                barcode != null, barcode == null ? "" : barcode,
                query == null, pattern,
                statusFilter == null ? "ALL" : statusFilter, today, pageable);
        return toPageResponse(result, LoanViewMapper.Actor.admin(), today);
    }

    /**
     * GET /api/loans/me（仅 READER，URL 规则保证；AC-AUTH-05）。
     * 读者身份只从 principal 取得，不声明 readerId 参数（传了也被忽略）。
     */
    @Transactional(readOnly = true)
    public PageResponse<LoanResponse> myLoans(LibraryUserPrincipal principal, String status,
                                              Integer page, Integer size) {
        Long readerId = principal.getReaderId();
        if (readerId == null) {
            // READER 账号缺少读者档案属于数据异常兜底（与 /api/readers/me 一致）
            throw ApiException.of(ErrorCode.READER_PROFILE_NOT_FOUND);
        }
        String statusFilter = parseStatusFilter(status);
        Pageable pageable = PageRequestParams.toPageable(page, size, null, SORT_WHITELIST, DEFAULT_SORT);
        LocalDate today = policy.today();
        Page<Loan> result = loanRepository.searchLoans(
                true, readerId, false, "", true, "",
                statusFilter == null ? "ALL" : statusFilter, today, pageable);
        return toPageResponse(result, LoanViewMapper.Actor.of(principal), today);
    }

    // ---- 内部辅助 ----

    /** 列表过滤用：null/空白 = 不过滤；非法值 → 400 + fieldErrors[status]。 */
    private static String parseStatusFilter(String status) {
        String trimmed = Texts.trimToNull(status);
        if (trimmed == null) {
            return null;
        }
        if (Set.of("ACTIVE", "OVERDUE", "RETURNED", "CURRENT").contains(trimmed)) {
            return trimmed;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                Map.of(), List.of(new ErrorResponse.FieldErrorItem("status",
                        "status 只能为 ACTIVE、OVERDUE、RETURNED 或 CURRENT")));
    }

    /** 批量映射：一次聚合各读者逾期数，避免 N+1（写法与 T04/T05 一致）。 */
    private PageResponse<LoanResponse> toPageResponse(Page<Loan> page, LoanViewMapper.Actor actor, LocalDate today) {
        List<Long> readerIds = page.getContent().stream().map(l -> l.getReader().getId()).distinct().toList();
        Map<Long, Long> overdueCounts = readerIds.isEmpty() ? Map.of()
                : readerRepository.countLoansByReaderIds(readerIds, today).stream()
                        .collect(Collectors.toMap(ReaderLoanCounts::getReaderId,
                                ReaderLoanCounts::getOverdueLoanCount));
        return PageResponse.from(page, loan -> viewMapper.toResponse(loan, actor, today,
                overdueCounts.getOrDefault(loan.getReader().getId(), 0L)));
    }

    /** 读者当前逾期未还条数（复用 T05 的批量聚合查询，只读）。 */
    private long overdueCount(Long readerId, LocalDate today) {
        List<ReaderLoanCounts> counts = readerRepository.countLoansByReaderIds(List.of(readerId), today);
        return counts.isEmpty() ? 0 : counts.get(0).getOverdueLoanCount();
    }
}