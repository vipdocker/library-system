package com.example.library.domain.repository;

import java.util.List;

import com.example.library.domain.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 分类 Repository（共享契约，T01 预置；T04 可新增方法，禁止修改已有签名）。
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    boolean existsByNameIgnoreCase(String name);

    // ---- T04 新增（分类 CRUD）----

    /** 更新时的重名检查：排除自身。 */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /** 分类列表：按名称排序（列 COLLATE NOCASE，ASCII 不区分大小写）。 */
    List<Category> findAllByOrderByNameAsc();
}