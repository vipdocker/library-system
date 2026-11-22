package com.example.library.common.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse.FieldErrorItem;
import tools.jackson.databind.JsonNode;

record ProxySettingsRequest(boolean enabled, String host, Integer port) {
    static ProxySettingsRequest parse(JsonNode body) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (body == null || !body.isObject()) { throw invalid(List.of(new FieldErrorItem("body", "必须为 JSON 对象"))); }
        body.propertyNames().forEach(name -> {
            if (!Set.of("enabled", "host", "port").contains(name)) { errors.add(new FieldErrorItem(name, "不支持的字段")); }
        });
        JsonNode enabled = body.get("enabled"), host = body.get("host"), port = body.get("port");
        if (enabled == null || !enabled.isBoolean()) { errors.add(new FieldErrorItem("enabled", "必须为布尔值")); }
        if (host != null && !host.isNull() && !host.isTextual()) { errors.add(new FieldErrorItem("host", "必须为字符串或 null")); }
        if (port != null && !port.isNull() && (!port.isIntegralNumber() || !port.canConvertToInt())) {
            errors.add(new FieldErrorItem("port", "必须为整数或 null"));
        }
        if (!errors.isEmpty()) { throw invalid(errors); }
        String normalized = null;
        try { normalized = ProxyAddress.normalize(host == null || host.isNull() ? null : host.asText()); }
        catch (IllegalArgumentException ex) { errors.add(new FieldErrorItem("host", "代理主机地址不合法")); }
        Integer number = port == null || port.isNull() ? null : port.intValue();
        if (number != null && (number < 1 || number > 65535)) { errors.add(new FieldErrorItem("port", "端口须为 1 至 65535")); }
        if (normalized == null && (number != null || enabled.booleanValue())) { errors.add(new FieldErrorItem("host", "需要完整主机地址")); }
        if (number == null && (normalized != null || enabled.booleanValue())) { errors.add(new FieldErrorItem("port", "需要完整端口")); }
        if (!errors.isEmpty()) { throw invalid(errors); }
        return new ProxySettingsRequest(enabled.booleanValue(), normalized, number);
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(), Map.of(), errors);
    }
}
