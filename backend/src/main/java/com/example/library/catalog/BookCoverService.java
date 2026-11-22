package com.example.library.catalog;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.domain.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookCoverService {
    private final BookRepository books;
    private final BookCoverRepository covers;

    public BookCoverService(BookRepository books, BookCoverRepository covers) {
        this.books = books;
        this.covers = covers;
    }

    public record Content(byte[] bytes, String mediaType) {
    }

    @Transactional(readOnly = true)
    public Content read(Long id) {
        if (!books.existsById(id)) {
            throw ApiException.of(ErrorCode.BOOK_NOT_FOUND);
        }
        var cover = covers.read(id);
        if (cover == null) {
            throw ApiException.of(ErrorCode.COVER_NOT_FOUND);
        }
        return new Content(cover.bytes(), cover.mediaType());
    }
}
