package com.example.library.catalog.settings;

import java.io.IOException;
import java.security.Principal;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADMIN-only via the existing /api/** fallback; PUT is CSRF-protected by the security chain.
 * The body is read from the real stream with a 4KiB cap (not just Content-Length); responses and
 * errors are no-store and never contain the AppKey. The controller does no persistence itself.
 */
@RestController
@RequestMapping("/api/admin/settings/showapi")
public class ShowApiSettingsController {
    static final int MAX_BODY_BYTES = 4 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final ShowApiSettingsService service;
    private final GlobalExceptionHandler errors;

    public ShowApiSettingsController(ShowApiSettingsService service, GlobalExceptionHandler errors) {
        this.service = service;
        this.errors = errors;
    }

    @GetMapping
    public ResponseEntity<ShowApiSettingsView> get() {
        return ok(ShowApiSettingsView.from(service.snapshot()));
    }

    @PutMapping
    public ResponseEntity<ShowApiSettingsView> put(HttpServletRequest request, Principal principal) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) { throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE); }
        JsonNode body;
        try {
            body = MAPPER.readTree(bytes);
        } catch (JacksonException ex) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "请求体缺失或不是合法的 JSON");
        }
        return ok(ShowApiSettingsView.from(service.replace(body, principal.getName())));
    }

    private static ResponseEntity<ShowApiSettingsView> ok(ShowApiSettingsView view) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(view);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException exception, HttpServletRequest request) {
        return noStore(errors.handleApi(exception, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> other(Exception exception, HttpServletRequest request) {
        return noStore(exception instanceof DataAccessException data
                ? errors.handleDataAccess(data, request) : errors.handleUnexpected(exception, request));
    }

    private static ResponseEntity<ErrorResponse> noStore(ResponseEntity<ErrorResponse> standard) {
        return ResponseEntity.status(standard.getStatusCode()).header("Cache-Control", "no-store").body(standard.getBody());
    }
}