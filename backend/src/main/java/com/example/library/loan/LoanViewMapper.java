package com.example.library.loan;

import java.time.LocalDate;

import com.example.library.common.config.LibraryProperties;
import com.example.library.domain.entity.Book;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.Role;
import com.example.library.loan.dto.LoanResponse;
import com.example.library.security.LibraryUserPrincipal;
import org.springframework.stereotype.Component;

/**
 * Loan → LoanResponse 视图映射（design-v0.1 第 8.3 节）。
 * status/overdueDays/renewable 为派生值，全部经 LoanPolicy 计算（第 6/9.6 节）。
 * renewable 叠加调用者语境：ADMIN 恒可按业务规则续借；READER 仅当 D2 开关允许且是本人借阅
 * （第 7 节；服务端续借时仍会完整校验，这里只是 UI 提示）。
 * 调用方必须在事务内使用（LAZY 关联）。
 */
@Component
public class LoanViewMapper {

    /** 当前调用者语境：角色 + 读者档案 id（ADMIN 无档案）。 */
    public record Actor(Role role, Long readerId) {

        public static Actor admin() {
            return new Actor(Role.ADMIN, null);
        }

        public static Actor of(LibraryUserPrincipal principal) {
            return new Actor(principal.getRole(), principal.getReaderId());
        }
    }

    private final LoanPolicy policy;
    private final LibraryProperties properties;

    public LoanViewMapper(LoanPolicy policy, LibraryProperties properties) {
        this.policy = policy;
        this.properties = properties;
    }

    /**
     * @param readerOverdueCount 该读者当前逾期未还条数（含本条，若本条逾期）。
     *                           “其他逾期”= 总数减去本条自身逾期，保证与第 9.4 节第 5 步语义一致。
     */
    public LoanResponse toResponse(Loan loan, Actor actor, LocalDate today, long readerOverdueCount) {
        Reader reader = loan.getReader();
        BookCopy copy = loan.getCopy();
        Book book = copy.getBook();
        boolean hasOtherOverdue = readerOverdueCount - (policy.isOverdue(loan, today) ? 1 : 0) > 0;
        boolean businessRenewable = policy.renewable(loan, reader, hasOtherOverdue, today);
        boolean actorMayRenew = actor.role() == Role.ADMIN
                || (properties.getLoan().isReaderSelfRenew()
                        && actor.readerId() != null && actor.readerId().equals(reader.getId()));
        return new LoanResponse(
                loan.getId(),
                new LoanResponse.ReaderRef(reader.getId(), reader.getCardNo(), reader.getName()),
                new LoanResponse.CopyRef(copy.getId(), copy.getBarcode()),
                new LoanResponse.BookRef(book.getId(), book.getTitle(), book.getAuthor(), book.getIsbn()),
                loan.getBorrowDate().toString(),
                loan.getDueDate().toString(),
                loan.getReturnDate() == null ? null : loan.getReturnDate().toString(),
                loan.getRenewCount(),
                policy.maxRenewals(),
                policy.statusOf(loan, today).name(),
                policy.overdueDays(loan, today),
                businessRenewable && actorMayRenew);
    }
}