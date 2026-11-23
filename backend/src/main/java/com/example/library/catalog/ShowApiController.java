package com.example.library.catalog;

import java.io.IOException;

import com.example.library.catalog.dto.ShowApiLookupResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/catalog/showapi (ADMIN via the existing /api/** fallback, session + CSRF). Input is
 * exactly {isbn:string}; the real stream is bounded to 4KiB. The controller only adapts HTTP and
 * never touches repositories. Responses and errors are no-store.
 */
@RestController
public class ShowApiController {
    static final int MAX_BODY_BYTES = 4 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final ShowApiService service;
    private final GlobalExceptionHandler errors;

    public ShowApiController(ShowApiService service, GlobalExceptionHandler errors) {
        this.service = service;
        this.errors = errors;
    }

    @PostMapping("/api/catalog/showapi")
    public ResponseEntity<ShowApiLookupResponse> lookup(HttpServletRequest request) throws IOException {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "此接口只接受 JSON 请求体，不接受 URL 参数");
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) { throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE); }
        JsonNode body;
        try {
            body = MAPPER.readTree(bytes);
        } catch (JacksonException ex) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "请求体缺失或不是合法的 JSON");
        }
        if (body == null || !body.isObject() || body.size() != 1
                || body.get("isbn") == null || !body.get("isbn").isTextual()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                    java.util.Map.of(), java.util.List.of(new ErrorResponse.FieldErrorItem("isbn", "请求体必须只包含字符串 isbn")));
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.lookup(body.get("isbn").asText()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> error(ApiException exception, HttpServletRequest request) {
        var standard = errors.handleApi(exception, request);
        var response = ResponseEntity.status(standard.getStatusCode()).header("Cache-Control", "no-store");
        if (exception.getCode() == ErrorCode.SHOWAPI_RATE_LIMITED) {
            response.header("Retry-After", String.valueOf(exception.getDetails().get("retryAfterSeconds")));
        }
        return response.body(standard.getBody());
    }
}