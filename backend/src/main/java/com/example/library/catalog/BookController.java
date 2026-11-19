package com.example.library.catalog;

import java.util.List;

import com.example.library.catalog.dto.BookCreateRequest;
import com.example.library.catalog.dto.BookDetailResponse;
import com.example.library.catalog.dto.BookSummaryResponse;
import com.example.library.catalog.dto.BookUpdateRequest;
import com.example.library.catalog.dto.CopyBatchRequest;
import com.example.library.catalog.dto.CopyResponse;
import com.example.library.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图书与副本接口（design-v0.1 第 8.4 节）。
 * 授权由 SecurityConfig URL 规则保证：GET /api/books、/api/books/{id} 已登录即可；
 * 写操作与 /api/books/{id}/copies 仅 ADMIN（AC-AUTH-04、AC-SRCH-02）。
 */
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;
    private final CopyService copyService;

    public BookController(BookService bookService, CopyService copyService) {
        this.bookService = bookService;
        this.copyService = copyService;
    }

    /** 200 Page<BookSummary>；q/field/categoryId/availableOnly/page/size/sort。 */
    @GetMapping
    public PageResponse<BookSummaryResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean availableOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return bookService.search(q, field, categoryId, availableOnly, page, size, sort);
    }

    /** 200 BookDetail。 */
    @GetMapping("/{id}")
    public BookDetailResponse detail(@PathVariable Long id) {
        return bookService.detail(id);
    }

    /** 201 BookDetail。 */
    @PostMapping
    public ResponseEntity<BookDetailResponse> create(@Valid @RequestBody BookCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookService.create(request));
    }

    /** 200 BookDetail。 */
    @PutMapping("/{id}")
    public BookDetailResponse update(@PathVariable Long id, @Valid @RequestBody BookUpdateRequest request) {
        return bookService.update(id, request);
    }

    /** 204（同时删除其所有副本）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bookService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 200 Copy[]（ADMIN）。 */
    @GetMapping("/{id}/copies")
    public List<CopyResponse> listCopies(@PathVariable Long id) {
        return copyService.listForBook(id);
    }

    /** 201 Copy[]（新建的副本）。 */
    @PostMapping("/{id}/copies")
    public ResponseEntity<List<CopyResponse>> addCopies(@PathVariable Long id,
                                                        @Valid @RequestBody CopyBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(copyService.addCopies(id, request.count()));
    }
}