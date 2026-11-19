package com.example.library.catalog;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.library.catalog.dto.CategoryRequest;
import com.example.library.catalog.dto.CategoryResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.domain.entity.Category;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.BookRepository.CategoryBookCount;
import com.example.library.domain.repository.CategoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分类服务（design-v0.1 第 8.4 节分类接口，AC-CAT-01）。
 * 名称唯一性忽略大小写：服务层预检 + 数据库 COLLATE NOCASE 唯一约束兜底（第 9.5 节）。
 * 删除仍被图书引用的分类 → 409 CATEGORY_IN_USE（外键 RESTRICT 兜底）。
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;
    private final Clock clock;

    public CategoryService(CategoryRepository categoryRepository, BookRepository bookRepository, Clock clock) {
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.clock = clock;
    }

    /** 分类列表：不分页，按名称排序，bookCount 一次聚合。 */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        Map<Long, Long> counts = bookRepository.countBooksByCategory().stream()
                .collect(Collectors.toMap(CategoryBookCount::getCategoryId, CategoryBookCount::getBookCount));
        return categoryRepository.findAllByOrderByNameAsc().stream()
                .map(c -> CategoryResponse.from(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw ApiException.of(ErrorCode.CATEGORY_NAME_DUPLICATE);
        }
        Category category = new Category(request.name(), request.description(), clock.instant());
        category = saveConflictSafe(category);
        return CategoryResponse.from(category, 0);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.CATEGORY_NOT_FOUND));
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw ApiException.of(ErrorCode.CATEGORY_NAME_DUPLICATE);
        }
        category.setName(request.name());
        category.setDescription(request.description());
        category.setUpdatedAt(clock.instant());
        category = saveConflictSafe(category);
        return CategoryResponse.from(category, bookRepository.countByCategoryId(id));
    }

    @Transactional
    public void delete(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.CATEGORY_NOT_FOUND));
        if (bookRepository.existsByCategoryId(id)) {
            throw ApiException.of(ErrorCode.CATEGORY_IN_USE);
        }
        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 外键 RESTRICT 兜底（第 9.5 节）：仍被引用时转成对应 409
            throw ApiException.of(ErrorCode.CATEGORY_IN_USE);
        }
    }

    private Category saveConflictSafe(Category category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.of(ErrorCode.CATEGORY_NAME_DUPLICATE);
        }
    }
}