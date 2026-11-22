package com.example.library.catalog.dto;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import com.example.library.domain.entity.Book;

/**
 * BookDetail（design-v0.1 第 8.3 节）：BookSummary + { description|null, createdAt, updatedAt }。
 * 时间戳显式格式化为 ISO-8601 UTC 字符串（第 8.1 节），不依赖 Jackson 默认配置；
 * 截断到毫秒，与数据库 epoch 毫秒存储精度一致（创建响应与后续读取完全相同）。
 */
public record BookDetailResponse(
        Long id,
        String isbn,
        String title,
        String author,
        String publisher,
        Integer publishYear,
        CategoryRef category,
        long totalCopies,
        long availableCopies,
        String description,
        String createdAt,
        String updatedAt,
        BookCoverResponse cover,
        String openLibraryIsbn,
        String metadataSourceUrl) {

    public static BookDetailResponse from(Book book, CategoryRef category, long totalCopies, long availableCopies,
                                           BookCoverResponse cover) {
        return new BookDetailResponse(book.getId(), book.getIsbn(), book.getTitle(), book.getAuthor(),
                book.getPublisher(), book.getPublishYear(), category, totalCopies, availableCopies,
                book.getDescription(),
                DateTimeFormatter.ISO_INSTANT.format(book.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)),
                DateTimeFormatter.ISO_INSTANT.format(book.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS)),
                cover, book.getOpenLibraryIsbn(), book.getOpenLibraryIsbn() == null ? null
                        : "https://openlibrary.org/isbn/" + book.getOpenLibraryIsbn());
    }
}