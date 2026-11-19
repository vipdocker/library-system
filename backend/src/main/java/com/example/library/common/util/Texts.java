package com.example.library.common.util;

/**
 * 字符串入参约定（design-v0.1 第 8.1 节）：去掉首尾空白；去空白后为空的可选字段按 null 处理。
 * 注意：口令字段不做 trim（避免改变用户设置的凭据）。
 */
public final class Texts {

    private Texts() {
    }

    public static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}