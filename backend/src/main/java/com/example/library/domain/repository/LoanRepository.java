package com.example.library.domain.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import com.example.library.domain.entity.Loan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 借阅 Repository（共享契约，T01 预置；T06 可新增方法，禁止修改已有签名）。
 * “active/在借” = returned_at IS NULL；“逾期” = 未还且 due_date < today（应还日当天不算逾期）。
 * markReturnedIfActive 为条件 UPDATE（第 9.3 节原子关闭），返回受影响行数。
 */
public interface LoanRepository extends JpaRepository<Loan, Long> {

    boolean existsByCopyId(Long copyId);

    boolean existsByCopyBookId(Long bookId);

    boolean existsByReaderId(Long readerId);

    /** 在借记录（ux_loan_active_copy 保证同一副本至多 1 条）。 */
    @Query("select l from Loan l where l.copy.id = :copyId and l.returnedAt is null")
    Optional<Loan> findActiveByCopyId(@Param("copyId") Long copyId);

    @Query("""
            select case when count(l) > 0 then true else false end from Loan l
            where l.copy.book.id = :bookId and l.returnedAt is null
            """)
    boolean existsActiveByCopyBookId(@Param("bookId") Long bookId);

    @Query("""
            select count(l) from Loan l
            where l.reader.id = :readerId and l.returnedAt is null
            """)
    long countActiveByReaderId(@Param("readerId") Long readerId);

    @Query("""
            select case when count(l) > 0 then true else false end from Loan l
            where l.reader.id = :readerId and l.returnedAt is null and l.dueDate < :today
            """)
    boolean existsOverdueByReaderId(@Param("readerId") Long readerId, @Param("today") LocalDate today);

    /** D4②：同一读者禁止同时借同一书目的多个副本。 */
    @Query("""
            select case when count(l) > 0 then true else false end from Loan l
            where l.reader.id = :readerId and l.copy.book.id = :bookId and l.returnedAt is null
            """)
    boolean existsActiveByReaderIdAndBookId(@Param("readerId") Long readerId, @Param("bookId") Long bookId);

    /** UPDATE loan SET returned_at=?, return_date=?, return_operator_id=? WHERE id=? AND returned_at IS NULL。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Loan l
            set l.returnedAt = :returnedAt, l.returnDate = :returnDate, l.returnOperatorId = :operatorId
            where l.id = :id and l.returnedAt is null
            """)
    int markReturnedIfActive(@Param("id") Long id, @Param("returnedAt") Instant returnedAt,
                             @Param("returnDate") LocalDate returnDate, @Param("operatorId") Long operatorId);

    // ---- T06 新增（续借原子更新；只新增，不改已有签名/语义）----

    /**
     * 第 9.4 节续借的条件 UPDATE：
     * UPDATE loan SET due_date=?, renew_count=renew_count+1, last_renew_operator_id=?
     * WHERE id=? AND returned_at IS NULL AND renew_count=?（乐观条件防并发重复续借）。
     * 返回受影响行数；为 0 时调用方重新读取并返回对应错误码。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Loan l
            set l.dueDate = :newDueDate, l.renewCount = l.renewCount + 1, l.lastRenewOperatorId = :operatorId
            where l.id = :id and l.returnedAt is null and l.renewCount = :expectedRenewCount
            """)
    int renewIfEligible(@Param("id") Long id, @Param("newDueDate") LocalDate newDueDate,
                        @Param("expectedRenewCount") int expectedRenewCount, @Param("operatorId") Long operatorId);

    /**
     * 借阅列表检索（第 8.4 节）：readerId 精确、copyBarcode 精确、q 书名子串（lower LIKE，
     * %/_/\ 已由 LikeEscaper 转义）、status 派生过滤（ACTIVE/OVERDUE/RETURNED/CURRENT，ALL=不过滤）。
     * 布尔开关 + 非空参数表达可选条件，避免 SQLite 方言对 null 参数类型推断的差异（与 T04/T05 写法一致）。
     * 逾期语义：未还且 due_date < today（应还日当天不算逾期）。
     */
    @Query(value = """
            select l from Loan l
            where (:filterReader = false or l.reader.id = :readerId)
              and (:filterBarcode = false or l.copy.barcode = :barcode)
              and (:matchAll = true or lower(l.copy.book.title) like lower(:titlePattern) escape '\\')
              and (:status = 'ALL'
                  or (:status = 'RETURNED' and l.returnedAt is not null)
                  or (:status = 'CURRENT' and l.returnedAt is null)
                  or (:status = 'ACTIVE' and l.returnedAt is null and l.dueDate >= :today)
                  or (:status = 'OVERDUE' and l.returnedAt is null and l.dueDate < :today))
            """,
            countQuery = """
            select count(l) from Loan l
            where (:filterReader = false or l.reader.id = :readerId)
              and (:filterBarcode = false or l.copy.barcode = :barcode)
              and (:matchAll = true or lower(l.copy.book.title) like lower(:titlePattern) escape '\\')
              and (:status = 'ALL'
                  or (:status = 'RETURNED' and l.returnedAt is not null)
                  or (:status = 'CURRENT' and l.returnedAt is null)
                  or (:status = 'ACTIVE' and l.returnedAt is null and l.dueDate >= :today)
                  or (:status = 'OVERDUE' and l.returnedAt is null and l.dueDate < :today))
            """)
    Page<Loan> searchLoans(
            @Param("filterReader") boolean filterReader, @Param("readerId") Long readerId,
            @Param("filterBarcode") boolean filterBarcode, @Param("barcode") String barcode,
            @Param("matchAll") boolean matchAll, @Param("titlePattern") String titlePattern,
            @Param("status") String status, @Param("today") LocalDate today,
            Pageable pageable);
}