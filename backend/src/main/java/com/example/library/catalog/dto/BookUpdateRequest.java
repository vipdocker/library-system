package com.example.library.catalog.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 图书更新请求（design-v0.1 第 8.4 节）：与创建相同，但不含 initialCopies。
 */
public record BookUpdateRequest(
        @NotBlank String isbn,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 100) String author,
        @Size(max = 100) String publisher,
        @Min(1000) @Max(9999) Integer publishYear,
        Long categoryId,
        @Size(max = 2000) String description) {

    public BookUpdateRequest {
        isbn = Texts.trimToNull(isbn);
        title = Texts.trimToNull(title);
        author = Texts.trimToNull(author);
        publisher = Texts.trimToNull(publisher);
        description = Texts.trimToNull(description);
    }
}