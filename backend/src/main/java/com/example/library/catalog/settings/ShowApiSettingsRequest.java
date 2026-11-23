package com.example.library.catalog.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse.FieldErrorItem;
import tools.jackson.databind.JsonNode;

/** Strict PUT body: {enabled, appKeyAction KEEP|REPLACE|CLEAR, appKey?, expectedRevision}. */
record ShowApiSettingsRequest(boolean enabled, String action, String appKey, long expectedRevision) {
    static final int MAX_KEY_LENGTH = 512;

    static ShowApiSettingsRequest parse(JsonNode body) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (body == null || !body.isObject()) { throw invalid(List.of(new FieldErrorItem("body", "必须为 JSON 对象"))); }
        body.propertyNames().forEach(name -> {
            if (!Set.of("enabled", "appKeyAction", "appKey", "expectedRevision").contains(name)) {
                errors.add(new FieldErrorItem(name, "不支持的字段"));
            }
        });
        JsonNode enabled = body.get("enabled"), action = body.get("appKeyAction"),
                key = body.get("appKey"), revision = body.get("expectedRevision");
        if (enabled == null || !enabled.isBoolean()) { errors.add(new FieldErrorItem("enabled", "必须为布尔值")); }
        String actionText = null;
        if (action == null || !action.isTextual()
                || !Set.of("KEEP", "REPLACE", "CLEAR").contains(actionText = action.asText())) {
            errors.add(new FieldErrorItem("appKeyAction", "只能为 KEEP、REPLACE 或 CLEAR"));
            actionText = null;
        }
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0) {
            errors.add(new FieldErrorItem("expectedRevision", "必须为非负整数"));
        }
        String trimmedKey = null;
        if ("REPLACE".equals(actionText)) {
            if (key == null || !key.isTextual()) {
                errors.add(new FieldErrorItem("appKey", "REPLACE 必须携带字符串 AppKey"));
            } else {
                trimmedKey = key.asText().trim();
                if (trimmedKey.isEmpty() || trimmedKey.length() > MAX_KEY_LENGTH) {
                    // Never echo the rejected value or any fragment of it.
                    errors.add(new FieldErrorItem("appKey", "AppKey 去除首尾空白后须为 1 至 512 个字符"));
                    trimmedKey = null;
                }
            }
        } else if (actionText != null && key != null) {
            errors.add(new FieldErrorItem("appKey", "KEEP/CLEAR 不能携带 appKey"));
        }
        if ("CLEAR".equals(actionText) && enabled != null && enabled.isBoolean() && enabled.booleanValue()) {
            errors.add(new FieldErrorItem("appKeyAction", "CLEAR 只能在停用时使用"));
        }
        if (!errors.isEmpty()) { throw invalid(errors); }
        return new ShowApiSettingsRequest(enabled.booleanValue(), actionText, trimmedKey, revision.asLong());
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(), Map.of(), errors);
    }
}