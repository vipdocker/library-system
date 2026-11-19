package com.example.library.common.error;

import java.util.List;
import java.util.Map;

/**
 * 业务异常：携带错误码、可选的自定义 message、details 与 fieldErrors。
 * message 不得包含口令、哈希等敏感信息。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;
    private final transient List<ErrorResponse.FieldErrorItem> fieldErrors;

    public ApiException(ErrorCode code) {
        this(code, code.getDefaultMessage(), Map.of(), List.of());
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of(), List.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, List.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details,
                        List<ErrorResponse.FieldErrorItem> fieldErrors) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiException of(ErrorCode code) {
        return new ApiException(code);
    }

    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException withDetails(ErrorCode code, Map<String, Object> details) {
        return new ApiException(code, code.getDefaultMessage(), details);
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public List<ErrorResponse.FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }
}