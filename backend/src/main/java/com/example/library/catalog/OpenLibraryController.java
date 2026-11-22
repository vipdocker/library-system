package com.example.library.catalog;

import com.example.library.catalog.dto.OpenLibraryLookupResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The existing /api/** ADMIN fallback protects this endpoint; lookup never writes a book. */
@RestController
public class OpenLibraryController {
    private final OpenLibraryService service;
    private final GlobalExceptionHandler errors;

    public OpenLibraryController(OpenLibraryService service, GlobalExceptionHandler errors) {
        this.service = service;
        this.errors = errors;
    }

    @GetMapping("/api/catalog/open-library")
    public ResponseEntity<OpenLibraryLookupResponse> lookup(@RequestParam String isbn, HttpServletRequest request) {
        if (request.getParameterMap().size() != 1 || request.getParameterValues("isbn").length != 1) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "此接口只接受一个 ISBN 参数");
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.lookup(isbn));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> error(ApiException exception, HttpServletRequest request) {
        var standard = errors.handleApi(exception, request);
        var response = ResponseEntity.status(standard.getStatusCode()).header("Cache-Control", "no-store");
        if (exception.getCode() == ErrorCode.OPEN_LIBRARY_RATE_LIMITED) {
            response.header("Retry-After", String.valueOf(exception.getDetails().get("retryAfterSeconds")));
        }
        return response.body(standard.getBody());
    }
}
