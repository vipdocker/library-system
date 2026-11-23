package com.example.library.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.library.catalog.dto.BookCreateRequest;
import com.example.library.catalog.dto.BookDetailResponse;
import com.example.library.catalog.dto.BookSourceRequest;
import com.example.library.catalog.dto.BookUpdateRequest;
import com.example.library.catalog.dto.CoverChangeRequest;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse.FieldErrorItem;
import com.example.library.common.util.IsbnUtils;
import org.springframework.stereotype.Service;

/** HTTP writes prepare/validate images before entering the separate BookService transaction proxy. */
@Service
public class BookWriteService {
    private static final Set<String> PROVIDERS = Set.of("OPEN_LIBRARY", "SHOWAPI");
    private final BookService books;
    private final CoverImageValidator validator;

    public BookWriteService(BookService books, CoverImageValidator validator) {
        this.books = books;
        this.validator = validator;
    }

    /** Resolved generic source; null means KEEP (no source change on update, none on create). */
    public record ResolvedSource(String provider, String isbn) {
    }

    public record PreparedCover(String action, CoverImageValidator.Image image, String sourceIsbn, String sourceProvider) {
    }

    public BookDetailResponse create(BookCreateRequest request) {
        var cover = prepare(request.cover());
        var source = metadataSource(request.openLibraryIsbn(), request.metadataSource());
        return books.create(request, cover, source);
    }

    public BookDetailResponse update(Long id, BookUpdateRequest request) {
        var cover = prepare(request.cover());
        var source = metadataSource(request.openLibraryIsbn(), request.metadataSource());
        return books.update(id, request, cover, source);
    }

    /** Legacy openLibraryIsbn and generic metadataSource are mutually exclusive; both mean OL/ShowAPI only. */
    static ResolvedSource metadataSource(String legacyIsbn, BookSourceRequest reference) {
        if (legacyIsbn != null && reference != null) {
            throw invalid("metadataSource", "openLibraryIsbn 与 metadataSource 不能同时提供");
        }
        if (reference != null) { return resolve(reference, "metadataSource"); }
        if (legacyIsbn != null) { return new ResolvedSource("OPEN_LIBRARY", sourceIsbn(legacyIsbn)); }
        return null;
    }

    private PreparedCover prepare(CoverChangeRequest request) {
        if (request == null) {
            return new PreparedCover("KEEP", null, null, null);
        }
        if (!"KEEP".equals(request.action()) && !"REMOVE".equals(request.action()) && !"REPLACE".equals(request.action())) {
            throw CoverImageValidator.invalid("封面 action 只能为 KEEP、REMOVE 或 REPLACE");
        }
        ResolvedSource source = coverSource(request.sourceIsbn(), request.source());
        String isbn = source == null ? null : source.isbn();
        String provider = source == null ? null : source.provider();
        if (!"REPLACE".equals(request.action())) {
            if (request.mediaType() != null || request.base64() != null) {
                throw CoverImageValidator.invalid("KEEP/REMOVE 不能携带图片数据");
            }
            return new PreparedCover(request.action(), null, isbn, provider);
        }
        return new PreparedCover("REPLACE", validator.decode(request.mediaType(), request.base64()), isbn, provider);
    }

    private static ResolvedSource coverSource(String legacyIsbn, BookSourceRequest reference) {
        if (legacyIsbn != null && reference != null) {
            throw invalid("cover.source", "sourceIsbn 与 source 不能同时提供");
        }
        if (reference != null) { return resolve(reference, "cover.source"); }
        if (legacyIsbn != null) { return new ResolvedSource("OPEN_LIBRARY", sourceIsbn(legacyIsbn)); }
        return null;
    }

    private static ResolvedSource resolve(BookSourceRequest reference, String field) {
        if (reference.provider() == null || !PROVIDERS.contains(reference.provider())) {
            throw invalid(field + ".provider", "provider 只能为 OPEN_LIBRARY 或 SHOWAPI");
        }
        if (reference.isbn() == null) {
            throw invalid(field + ".isbn", "来源 ISBN 必填");
        }
        return new ResolvedSource(reference.provider(), sourceIsbn(reference.isbn()));
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

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                Map.of(), List.of(new FieldErrorItem(field, message)));
    }
}