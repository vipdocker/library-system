package com.example.library.catalog.dto;

import com.example.library.domain.entity.Category;

/**
 * Category（design-v0.1 第 8.3 节）：{ id, name, description|null, bookCount }。
 */
public record CategoryResponse(Long id, String name, String description, long bookCount) {

    public static CategoryResponse from(Category category, long bookCount) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDescription(), bookCount);
    }
}