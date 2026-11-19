package com.example.library.reader;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.util.LikeEscaper;
import com.example.library.common.util.Texts;
import com.example.library.common.web.PageRequestParams;
import com.example.library.common.web.PageResponse;
import com.example.library.domain.entity.AppUser;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.ReaderRepository;
import com.example.library.domain.repository.ReaderRepository.ReaderLoanCounts;
import com.example.library.reader.dto.ReaderCreateRequest;
import com.example.library.reader.dto.ReaderPasswordResetRequest;
import com.example.library.reader.dto.ReaderResponse;
import com.example.library.reader.dto.ReaderUpdateRequest;
import com.example.library.security.LibraryUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读者服务（design-v0.1 第 6/8.4 节，AC-RDR-01..06、AC-AUTH-02 停用联动、AC-AUTH-05 /me）。
 * - 创建读者时同一事务内创建 READER 账号；卡号系统生成 R%06d(id)：先写 PENDING-UUID，
 *   拿到自增 id 后同一事务内回填（第 6 节，与 T04 条码写法一致）；
 * - reader.status 是唯一事实来源：更新状态时同一事务同步 app_user.enabled（ACTIVE→1，DISABLED→0），
 *   停用后重新登录被拒（AUTH_ACCOUNT_DISABLED，AC-AUTH-02）；
 * - 删除前检查借阅历史：在借 → READER_HAS_ACTIVE_LOANS；有历史 → READER_HAS_LOAN_HISTORY；
 *   无历史时同一事务内删除档案与账号（外键 RESTRICT 兜底，第 9.5 节）；
 * - /me 读者身份只从 principal 取得，不接收 readerId 参数（防 IDOR，第 7 节）；
 * - activeLoanCount/overdueLoanCount 为派生值，读取时聚合（逾期=未还且 due_date < today，第 6/9.1 节）；
 * - 口令只写入哈希，任何响应与日志不输出口令或哈希（第 7/12 节）。
 */
@Service
public class ReaderService {

    private static final Set<String> SORT_WHITELIST = Set.of("name", "cardNo", "createdAt");
    /** 方案 8.4 只规定读者排序白名单，未指定默认值；取 name,asc（与图书列表默认 title,asc 风格一致）。 */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final ReaderRepository readerRepository;
    private final AppUserRepository appUserRepository;
    private final LoanRepository loanRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public ReaderService(ReaderRepository readerRepository, AppUserRepository appUserRepository,
                         LoanRepository loanRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.readerRepository = readerRepository;
        this.appUserRepository = appUserRepository;
        this.loanRepository = loanRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** GET /api/readers：q（姓名子串、卡号精确、用户名精确忽略大小写）+ status 过滤 + 分页 + 白名单排序。 */
    @Transactional(readOnly = true)
    public PageResponse<ReaderResponse> search(String q, String status, Integer page, Integer size, String sort) {
        String query = Texts.trimToNull(q);
        ReaderStatus statusFilter = parseStatusFilter(status);
        Pageable pageable = PageRequestParams.toPageable(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);

        String pattern = "%" + LikeEscaper.escape(query == null ? "" : query) + "%";
        Page<Reader> result = readerRepository.searchReaders(
                query == null, pattern, query == null ? "" : query,
                statusFilter != null, statusFilter == null ? ReaderStatus.ACTIVE : statusFilter, pageable);
        BatchContext context = batchContext(result.getContent());
        return PageResponse.from(result, reader -> toResponse(reader, context));
    }

    /** GET /api/readers/{id}。 */
    @Transactional(readOnly = true)
    public ReaderResponse detail(Long id) {
        Reader reader = requireReader(id);
        return toResponse(reader, batchContext(List.of(reader)));
    }

    /** POST /api/readers：同一事务内创建 READER 账号 + 档案并回填卡号。 */
    @Transactional
    public ReaderResponse create(ReaderCreateRequest request) {
        if (appUserRepository.existsByUsernameIgnoreCase(request.username())) {
            throw ApiException.of(ErrorCode.USERNAME_DUPLICATE);
        }
        var now = clock.instant();
        AppUser user = new AppUser(request.username(), passwordEncoder.encode(request.initialPassword()),
                Role.READER, true, now);
        try {
            user = appUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // COLLATE NOCASE 唯一约束兜底（并发/竞争创建）
            throw ApiException.of(ErrorCode.USERNAME_DUPLICATE);
        }
        Reader reader = new Reader(user.getId(), "PENDING-" + UUID.randomUUID(), request.name(),
                ReaderStatus.ACTIVE, now);
        reader.setPhone(request.phone());
        reader.setEmail(request.email());
        reader = readerRepository.saveAndFlush(reader);
        reader.setCardNo(String.format("R%06d", reader.getId()));
        reader = readerRepository.saveAndFlush(reader);
        return ReaderResponse.from(reader, user.getUsername(), 0, 0);
    }

    /** PUT /api/readers/{id}：更新资料；状态与账号 enabled 同一事务同步（AC-RDR-02）。 */
    @Transactional
    public ReaderResponse update(Long id, ReaderUpdateRequest request) {
        ReaderStatus target = parseRequiredStatus(request.status());
        Reader reader = requireReader(id);
        AppUser user = requireAccount(reader.getUserId());
        var now = clock.instant();
        reader.setName(request.name());
        reader.setPhone(request.phone());
        reader.setEmail(request.email());
        reader.setStatus(target);
        reader.setUpdatedAt(now);
        reader = readerRepository.saveAndFlush(reader);
        user.setEnabled(target == ReaderStatus.ACTIVE);
        user.setUpdatedAt(now);
        appUserRepository.saveAndFlush(user);
        return toResponse(reader, batchContext(List.of(reader)));
    }

    /** PUT /api/readers/{id}/password：管理员重置口令（AC-RDR-05）。 */
    @Transactional
    public void resetPassword(Long id, ReaderPasswordResetRequest request) {
        Reader reader = requireReader(id);
        AppUser user = requireAccount(reader.getUserId());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(clock.instant());
        appUserRepository.saveAndFlush(user);
    }

    /** DELETE /api/readers/{id}：先查在借，再查历史；无历史时同一事务删除档案与账号（AC-RDR-03）。 */
    @Transactional
    public void delete(Long id) {
        Reader reader = requireReader(id);
        if (loanRepository.countActiveByReaderId(id) > 0) {
            throw ApiException.of(ErrorCode.READER_HAS_ACTIVE_LOANS);
        }
        if (loanRepository.existsByReaderId(id)) {
            throw ApiException.of(ErrorCode.READER_HAS_LOAN_HISTORY);
        }
        try {
            readerRepository.delete(reader);
            readerRepository.flush();
            appUserRepository.deleteById(reader.getUserId());
            appUserRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 外键 RESTRICT 兜底（第 9.5 节）：仍有借阅引用时转成对应 409
            throw ApiException.of(ErrorCode.READER_HAS_LOAN_HISTORY);
        }
    }

    /** GET /api/readers/me：读者身份只从 principal 取得（AC-RDR-06、AC-AUTH-05）。 */
    @Transactional(readOnly = true)
    public ReaderResponse me(LibraryUserPrincipal principal) {
        Reader reader = readerRepository.findByUserId(principal.getUserId())
                .orElseThrow(() -> ApiException.of(ErrorCode.READER_PROFILE_NOT_FOUND));
        return toResponse(reader, batchContext(List.of(reader)));
    }

    // ---- 内部辅助 ----

    private Reader requireReader(Long id) {
        return readerRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.READER_NOT_FOUND));
    }

    /** 档案存在但账号缺失属于数据异常（user_id 外键保证不会发生）；走 500 兜底，不伪造业务码。 */
    private AppUser requireAccount(Long userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("读者账号缺失 userId=" + userId));
    }

    /** 列表过滤用：null/空白 = 不过滤；非法值 → 400 + fieldErrors[status]。 */
    private static ReaderStatus parseStatusFilter(String status) {
        String trimmed = Texts.trimToNull(status);
        if (trimmed == null) {
            return null;
        }
        return parseRequiredStatus(trimmed);
    }

    private static ReaderStatus parseRequiredStatus(String status) {
        if ("ACTIVE".equals(status)) {
            return ReaderStatus.ACTIVE;
        }
        if ("DISABLED".equals(status)) {
            return ReaderStatus.DISABLED;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                Map.of(), List.of(new ErrorResponse.FieldErrorItem("status", "status 只能为 ACTIVE 或 DISABLED")));
    }

    /** 批量上下文：一次取账号用户名 + 一次聚合借阅计数，避免 N+1（与 T04 写法一致）。 */
    private record BatchContext(Map<Long, AppUser> users, Map<Long, ReaderLoanCounts> loanCounts) {
    }

    private BatchContext batchContext(List<Reader> readers) {
        List<Long> userIds = readers.stream().map(Reader::getUserId).toList();
        Map<Long, AppUser> users = userIds.isEmpty() ? Map.of()
                : appUserRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<Long> readerIds = readers.stream().map(Reader::getId).toList();
        Map<Long, ReaderLoanCounts> counts = readerIds.isEmpty() ? Map.of()
                : readerRepository.countLoansByReaderIds(readerIds, LocalDate.now(clock)).stream()
                        .collect(Collectors.toMap(ReaderLoanCounts::getReaderId, Function.identity()));
        return new BatchContext(users, counts);
    }

    private ReaderResponse toResponse(Reader reader, BatchContext context) {
        AppUser user = context.users().get(reader.getUserId());
        ReaderLoanCounts counts = context.loanCounts().get(reader.getId());
        return ReaderResponse.from(reader, user == null ? null : user.getUsername(),
                counts == null ? 0 : counts.getActiveLoanCount(),
                counts == null ? 0 : counts.getOverdueLoanCount());
    }
}