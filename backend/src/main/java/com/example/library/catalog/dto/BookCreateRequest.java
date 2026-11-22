package com.example.library.catalog.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 图书创建请求（design-v0.1 第 8.4 节）：
 * {isbn, title, author, publisher?, publishYear?, categoryId?, description?, initialCopies?(0..50, 默认 1)}。
 * ISBN 的规范化与严格校验位检查（D4③）在服务层执行（IsbnUtils），失败返回 400 + fieldErrors。
 * 紧凑构造器先 trim 再校验（第 8.1 节）。
 */
public record BookCreateRequest(
        @NotBlank String isbn,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 100) String author,
        @Size(max = 100) String publisher,
        @Min(1000) @Max(9999) Integer publishYear,
        Long categoryId,
        @Size(max = 2000) String description,
        @Min(0) @Max(50) Integer initialCopies,
        CoverChangeRequest cover,
        String openLibraryIsbn) {

    public BookCreateRequest {
        isbn = Texts.trimToNull(isbn);
        title = Texts.trimToNull(title);
        author = Texts.trimToNull(author);
        publisher = Texts.trimToNull(publisher);
        description = Texts.trimToNull(description);
    }
}