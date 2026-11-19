package com.example.library.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 口令策略（design §7：8–64 位，至少一个字母和一个数字；支撑 AC-RDR-01、AC-AUTH-10）。 */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Admin@12345", "Reader@123", "abcd1234", "A1bbbbbb", "1234567a"})
    @DisplayName("合规口令")
    void valid(String password) {
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab1", "abcdefg", "abcdefgh", "12345678", "ABCDEFGH", "Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1X"})
    @DisplayName("不合规：过短/纯字母/纯数字/超长")
    void invalid(String password) {
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    @Test
    @DisplayName("边界：8 位与 64 位合规；null 不合规")
    void boundaries() {
        assertThat(PasswordPolicy.isValid("a1234567")).isTrue();
        assertThat(PasswordPolicy.isValid("a" + "1".repeat(63))).isTrue();
        assertThat(PasswordPolicy.isValid("a" + "1".repeat(64))).isFalse();
        assertThat(PasswordPolicy.isValid(null)).isFalse();
    }
}