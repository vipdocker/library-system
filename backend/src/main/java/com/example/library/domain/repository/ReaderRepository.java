package com.example.library.domain.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.example.library.domain.entity.Reader;
import com.example.library.domain.enums.ReaderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 读者 Repository（共享契约，T01 预置；T05 可新增方法，禁止修改已有签名）。
 */
public interface ReaderRepository extends JpaRepository<Reader, Long> {

    Optional<Reader> findByCardNo(String cardNo);

    Optional<Reader> findByUserId(Long userId);

    // ---- T05 新增（读者检索 / 借阅计数聚合）----

    /**
     * 读者检索（design-v0.1 第 8.4 节）：姓名子串（lower LIKE，%/_/\ 已由 LikeEscaper 转义）、
     * 卡号精确、用户名精确（忽略大小写，与 COLLATE NOCASE 唯一性语义一致）；可按状态过滤。
     * Reader.userId 是显式外键列（无 JPA 关联），用 theta join 关联 AppUser。
     * 布尔开关 + 非空参数表达可选条件，避免 SQLite 方言对 null 参数类型推断的差异（与 T04 写法一致）。
     */
    @Query(value = """
            select r from Reader r, AppUser u
            where u.id = r.userId
              and (:hasStatus = false or r.status = :status)
              and (:matchAll = true
                  or lower(r.name) like lower(:pattern) escape '\\'
                  or r.cardNo = :exact
                  or lower(u.username) = lower(:exact))
            """,
            countQuery = """
            select count(r) from Reader r, AppUser u
            where u.id = r.userId
              and (:hasStatus = false or r.status = :status)
              and (:matchAll = true
                  or lower(r.name) like lower(:pattern) escape '\\'
                  or r.cardNo = :exact
                  or lower(u.username) = lower(:exact))
            """)
    Page<Reader> searchReaders(@Param("matchAll") boolean matchAll,
                               @Param("pattern") String pattern,
                               @Param("exact") String exact,
                               @Param("hasStatus") boolean hasStatus,
                               @Param("status") ReaderStatus status,
                               Pageable pageable);

    /** 读者列表/详情的派生计数：activeLoanCount=未还条数；overdueLoanCount=未还且 due_date < today 条数。 */
    interface ReaderLoanCounts {
        Long getReaderId();

        long getActiveLoanCount();

        long getOverdueLoanCount();
    }

    /**
     * 批量聚合读者的在借数与逾期数（一次查询，避免 N+1）。
     * 只读 Loan 实体，不修改 LoanRepository（T06 所有权）。
     */
    @Query("""
            select l.reader.id as readerId, count(l) as activeLoanCount,
                   sum(case when l.dueDate < :today then 1 else 0 end) as overdueLoanCount
            from Loan l
            where l.reader.id in :readerIds and l.returnedAt is null
            group by l.reader.id
            """)
    List<ReaderLoanCounts> countLoansByReaderIds(@Param("readerIds") Collection<Long> readerIds,
                                                 @Param("today") LocalDate today);
}