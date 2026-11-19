package com.example.library.domain.repository;

import java.util.List;

import com.example.library.domain.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 书目 Repository（共享契约，T01 预置；T04 可新增方法，禁止修改已有签名）。
 */
public interface BookRepository extends JpaRepository<Book, Long> {

    boolean existsByIsbn(String isbn);

    boolean existsByCategoryId(Long categoryId);

    // ---- T04 新增（图书 CRUD / 检索 / 分类计数）----

    /** 更新时的 ISBN 重复检查：排除自身。 */
    boolean existsByIsbnAndIdNot(String isbn, Long id);

    /** 分类列表中的 bookCount。 */
    long countByCategoryId(Long categoryId);

    /** 各分类的图书数（一次聚合，避免 N+1）。 */
    interface CategoryBookCount {
        Long getCategoryId();

        long getBookCount();
    }

    @Query("""
            select b.categoryId as categoryId, count(b) as bookCount
            from Book b
            where b.categoryId is not null
            group by b.categoryId
            """)
    List<CategoryBookCount> countBooksByCategory();

    /**
     * 图书检索（design-v0.1 第 8.4 节检索语义）。全部条件用布尔开关 + 非空参数表达，
     * 避免 SQLite 方言对 null 参数类型推断的差异：
     * - matchAll=true：q 为空，列出全部；
     * - title/author：lower(col) LIKE lower(:pattern) ESCAPE '\'，pattern 已由 LikeEscaper 转义并加 %；
     * - isbn：规范化后精确匹配（matchIsbn 仅在规范化成功时为 true）；
     * - availableOnly：存在 AVAILABLE 副本；
     * - hasCategory=false 时忽略 categoryId（调用方传 0）。
     */
    @Query(value = """
            select b from Book b
            where (:hasCategory = false or b.categoryId = :categoryId)
              and (:availableOnly = false or exists (
                  select c.id from BookCopy c
                  where c.book = b
                    and c.status = com.example.library.domain.enums.CopyStatus.AVAILABLE))
              and (:matchAll = true
                  or (:matchTitle = true and lower(b.title) like lower(:pattern) escape '\\')
                  or (:matchAuthor = true and lower(b.author) like lower(:pattern) escape '\\')
                  or (:matchIsbn = true and b.isbn = :isbn))
            """,
            countQuery = """
            select count(b) from Book b
            where (:hasCategory = false or b.categoryId = :categoryId)
              and (:availableOnly = false or exists (
                  select c.id from BookCopy c
                  where c.book = b
                    and c.status = com.example.library.domain.enums.CopyStatus.AVAILABLE))
              and (:matchAll = true
                  or (:matchTitle = true and lower(b.title) like lower(:pattern) escape '\\')
                  or (:matchAuthor = true and lower(b.author) like lower(:pattern) escape '\\')
                  or (:matchIsbn = true and b.isbn = :isbn))
            """)
    Page<Book> searchBooks(@Param("hasCategory") boolean hasCategory,
                           @Param("categoryId") Long categoryId,
                           @Param("availableOnly") boolean availableOnly,
                           @Param("matchAll") boolean matchAll,
                           @Param("matchTitle") boolean matchTitle,
                           @Param("matchAuthor") boolean matchAuthor,
                           @Param("matchIsbn") boolean matchIsbn,
                           @Param("pattern") String pattern,
                           @Param("isbn") String isbn,
                           Pageable pageable);
}