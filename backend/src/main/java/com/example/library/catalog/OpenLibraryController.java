package com.example.library.catalog;

import java.util.List;
import java.util.Map;
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
import org.springframework.web.context.request.async.DeferredResult;

/**
 * The existing /api/** ADMIN fallback protects this endpoint; lookup never writes a book.
 * Bounded cancellation (OL-OPT-v1): the request is asynchronous; standard async timeout/error/completion callbacks
 * unsubscribe this request only. There is no cancel endpoint or handle, and a browser abort is only observed when the
 * container notices it — the worker itself is always bounded by the 14 s query deadline.
 */
@RestController
public class OpenLibraryController {
    static final long ASYNC_TIMEOUT_MILLIS = 16_000;
    private final OpenLibraryService service;
    private final GlobalExceptionHandler errors;

    public OpenLibraryController(OpenLibraryService service, GlobalExceptionHandler errors) {
        this.service = service;
        this.errors = errors;
    }

    @GetMapping("/api/catalog/open-library")
    public DeferredResult<ResponseEntity<OpenLibraryLookupResponse>> lookup(@RequestParam String isbn, HttpServletRequest request) {
        if (request.getParameterMap().size() != 1 || request.getParameterValues("isbn").length != 1) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "此接口只接受一个 ISBN 参数");
        }
        var subscription = service.subscribe(isbn);
        var result = new DeferredResult<ResponseEntity<OpenLibraryLookupResponse>>(ASYNC_TIMEOUT_MILLIS);
        result.onTimeout(() -> {
            subscription.cancel();
            result.setErrorResult(new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(),
                    Map.of("reason", "TIMEOUT"), List.of()));
        });
        result.onError(failure -> subscription.cancel());
        result.onCompletion(subscription::cancel);
        subscription.result().whenComplete((body, failure) -> {
            if (failure == null) { result.setResult(ResponseEntity.ok().header("Cache-Control", "no-store").body(body)); }
            else { result.setErrorResult(OpenLibraryLookupState.unwrap(failure)); }
        });
        return result;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> error(ApiException exception, HttpServletRequest request) {
        var standard = errors.handleApi(exception, request);
        var response = ResponseEntity.status(standard.getStatusCode()).header("Cache-Control", "no-store");
        Object retry = exception.getDetails().get("retryAfterSeconds");
        if (exception.getCode() == ErrorCode.OPEN_LIBRARY_RATE_LIMITED && retry instanceof Integer seconds) {
            response.header("Retry-After", String.valueOf(seconds));
        }
        return response.body(standard.getBody());
    }
}
