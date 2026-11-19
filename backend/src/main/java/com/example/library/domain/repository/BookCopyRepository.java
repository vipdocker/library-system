package com.example.library.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.enums.CopyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 副本 Repository（共享契约，T01 预置；T04 可新增方法，禁止修改已有签名）。
 * mark* 为条件 UPDATE（design-v0.1 第 9.2/9.3 节原子占用/释放），返回受影响行数；
 * 调用方必须在事务内，并以行数 != 1 判定竞争失败。
 */
public interface BookCopyRepository extends JpaRepository<BookCopy, Long> {

    Optional<BookCopy> findByBarcode(String barcode);

    long countByBookId(Long bookId);

    long countByBookIdAndStatus(Long bookId, CopyStatus status);

    /** UPDATE book_copy SET status='BORROWED' WHERE id=? AND status='AVAILABLE'。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update BookCopy c
            set c.status = com.example.library.domain.enums.CopyStatus.BORROWED, c.updatedAt = :now
            where c.id = :id and c.status = com.example.library.domain.enums.CopyStatus.AVAILABLE
            """)
    int markBorrowedIfAvailable(@Param("id") Long id, @Param("now") Instant now);

    /** UPDATE book_copy SET status='AVAILABLE' WHERE id=? AND status='BORROWED'。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update BookCopy c
            set c.status = com.example.library.domain.enums.CopyStatus.AVAILABLE, c.updatedAt = :now
            where c.id = :id and c.status = com.example.library.domain.enums.CopyStatus.BORROWED
            """)
    int markAvailableIfBorrowed(@Param("id") Long id, @Param("now") Instant now);

    // ---- T04 新增（副本管理 / 可借数量聚合）----

    /** 某书目的全部副本，按 id（即条码生成顺序）排序。 */
    java.util.List<BookCopy> findByBookIdOrderByIdAsc(Long bookId);

    /** 删除图书时一并删除其副本（调用方必须先检查借阅历史，且在同一事务内）。 */
    void deleteByBookId(Long bookId);

    /** 列表页批量计数：totalCopies 与 availableCopies（一次聚合，避免 N+1）。 */
    interface CopyCounts {
        Long getBookId();

        long getTotalCopies();

        long getAvailableCopies();
    }

    @Query("""
            select c.book.id as bookId, count(c) as totalCopies,
                   sum(case when c.status = com.example.library.domain.enums.CopyStatus.AVAILABLE
                       then 1 else 0 end) as availableCopies
            from BookCopy c
            where c.book.id in :bookIds
            group by c.book.id
            """)
    java.util.List<CopyCounts> countCopiesByBookIds(@Param("bookIds") java.util.Collection<Long> bookIds);
}