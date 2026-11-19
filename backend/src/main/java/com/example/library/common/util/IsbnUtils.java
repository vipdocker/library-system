package com.example.library.common.util;

/**
 * ISBN 工具（design-v0.1 第 8.4 节检索语义 + D4③ 严格校验）。
 * - normalize：去掉空格和连字符、x 转大写；长度不是 10/13 位或含非法字符时返回 null。
 *   检索用 normalize（规范化后长度不符 → 空结果，不报错）。
 * - isValid：normalize 之后再做校验位检查（ISBN-10 末位可为 X；ISBN-13 加权和）。
 *   新建/编辑图书用 isValid（校验位错误 → 400）。
 * ISBN-10 不转换成 13 位，按原样存储。
 */
public final class IsbnUtils {

    private IsbnUtils() {
    }

    /** 规范化；长度/字符不合法时返回 null（不做校验位检查）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '-' || c == ' ' || c == '\t') {
                continue;
            }
            sb.append(c == 'x' ? 'X' : c);
        }
        String s = sb.toString();
        if (s.matches("\\d{13}") || s.matches("\\d{10}") || s.matches("\\d{9}X")) {
            return s;
        }
        return null;
    }

    /** 规范化 + 校验位检查。 */
    public static boolean isValid(String raw) {
        String s = normalize(raw);
        if (s == null) {
            return false;
        }
        return s.length() == 13 ? isbn13ChecksumOk(s) : isbn10ChecksumOk(s);
    }

    private static boolean isbn10ChecksumOk(String s) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = s.charAt(i);
            int digit = c == 'X' ? 10 : c - '0';
            if (c == 'X' && i != 9) {
                return false;
            }
            sum += digit * (10 - i);
        }
        return sum % 11 == 0;
    }

    private static boolean isbn13ChecksumOk(String s) {
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            int digit = s.charAt(i) - '0';
            sum += digit * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }
}