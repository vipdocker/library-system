package com.example.library.common.error;

import java.util.List;
import java.util.Map;

/**
 * 统一错误响应（design-v0.1 第 8.1 节）。
 * fieldErrors 只在 400 校验错误时非空，否则为 []；details 可以为空对象 {}。
 * 任何情况下不得包含堆栈、SQL、口令或哈希。
 */
public record ErrorResponse(
        String code,
        String message,
        int status,
        String path,
        String timestamp,
        List<FieldErrorItem> fieldErrors,
        Map<String, Object> details) {

    public ErrorResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public record FieldErrorItem(String field, String message) {
    }
}