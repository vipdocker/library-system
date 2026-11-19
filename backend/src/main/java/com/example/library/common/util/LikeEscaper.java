package com.example.library.common.util;

/**
 * LIKE 字面量转义（design-v0.1 第 8.4 节检索语义）：
 * %、_、\ 先转义，配合 ESCAPE '\' 按字面匹配。SQL 仍必须使用参数绑定。
 */
public final class LikeEscaper {

    private LikeEscaper() {
    }

    public static String escape(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}