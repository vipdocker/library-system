package com.example.library.catalog;

import com.example.library.catalog.dto.BookCreateRequest;
import com.example.library.catalog.dto.BookDetailResponse;
import com.example.library.catalog.dto.BookUpdateRequest;
import com.example.library.catalog.dto.CoverChangeRequest;
import com.example.library.common.util.IsbnUtils;
import org.springframework.stereotype.Service;

/** HTTP writes prepare/validate images before entering the separate BookService transaction proxy. */
@Service
public class BookWriteService {
    private final BookService books;
    private final CoverImageValidator validator;

    public BookWriteService(BookService books, CoverImageValidator validator) {
        this.books = books;
        this.validator = validator;
    }

    public record PreparedCover(String action, CoverImageValidator.Image image, String sourceIsbn) {
    }

    public BookDetailResponse create(BookCreateRequest request) {
        var cover = prepare(request.cover());
        String source = sourceIsbn(request.openLibraryIsbn());
        return books.create(request, cover, source);
    }

    public BookDetailResponse update(Long id, BookUpdateRequest request) {
        var cover = prepare(request.cover());
        String source = sourceIsbn(request.openLibraryIsbn());
        return books.update(id, request, cover, source);
    }

    private PreparedCover prepare(CoverChangeRequest request) {
        if (request == null) {
            return new PreparedCover("KEEP", null, null);
        }
        if (!"KEEP".equals(request.action()) && !"REMOVE".equals(request.action()) && !"REPLACE".equals(request.action())) {
            throw CoverImageValidator.invalid("封面 action 只能为 KEEP、REMOVE 或 REPLACE");
        }
        String source = sourceIsbn(request.sourceIsbn());
        if (!"REPLACE".equals(request.action())) {
            if (request.mediaType() != null || request.base64() != null) {
                throw CoverImageValidator.invalid("KEEP/REMOVE 不能携带图片数据");
            }
            return new PreparedCover(request.action(), null, source);
        }
        return new PreparedCover("REPLACE", validator.decode(request.mediaType(), request.base64()), source);
    }

    static String sourceIsbn(String raw) {
        if (raw == null) {
            return null;
        }
        if (!IsbnUtils.isValid(raw)) {
            throw CoverImageValidator.invalid("来源 ISBN 格式或校验位错误");
        }
        return IsbnUtils.normalize(raw);
    }
}
