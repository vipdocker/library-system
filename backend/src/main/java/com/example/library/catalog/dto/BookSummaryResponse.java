package com.example.library.catalog.dto;

import com.example.library.domain.entity.Book;

/**
 * BookSummary（design-v0.1 第 8.3 节）：
 * { id, isbn, title, author, publisher|null, publishYear|null, category:{id,name}|null,
 *   totalCopies, availableCopies }。
 */
public record BookSummaryResponse(
        Long id,
        String isbn,
        String title,
        String author,
        String publisher,
        Integer publishYear,
        CategoryRef category,
        long totalCopies,
        long availableCopies) {

    public static BookSummaryResponse from(Book book, CategoryRef category, long totalCopies, long availableCopies) {
        return new BookSummaryResponse(book.getId(), book.getIsbn(), book.getTitle(), book.getAuthor(),
                book.getPublisher(), book.getPublishYear(), category, totalCopies, availableCopies);
    }
}