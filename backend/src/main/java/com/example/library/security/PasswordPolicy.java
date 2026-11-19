package com.example.library.security;

/**
 * 口令策略（design-v0.1 第 7 节）：8–64 位，至少包含一个字母和一个数字。
 * 供 T05（创建读者/重置口令）与 T14（修改本人口令）复用；不做 trim。
 */
public final class PasswordPolicy {

    public static final String MESSAGE = "口令必须为 8–64 位，且至少包含一个字母和一个数字";

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }
}