package com.example.library.common;

import com.example.library.common.util.IsbnUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** ISBN 规范化与严格校验位检查（D4③；支撑 AC-BOOK-01、AC-SRCH-01）。 */
class IsbnUtilsTest {

    @Test
    @DisplayName("AC-BOOK-01：合法 ISBN-13（含连字符/空格）规范化并通过校验")
    void validIsbn13() {
        assertThat(IsbnUtils.isValid("9787115428028")).isTrue();
        assertThat(IsbnUtils.isValid("978-7-115-42802-8")).isTrue();
        assertThat(IsbnUtils.isValid("978 7 115 42802 8")).isTrue();
        assertThat(IsbnUtils.normalize("978-7-115-42802-8")).isEqualTo("9787115428028");
    }

    @Test
    @DisplayName("AC-BOOK-01：合法 ISBN-10（末位 X，大小写均可）按原样规范化，不转 13 位")
    void validIsbn10WithX() {
        assertThat(IsbnUtils.isValid("080442957X")).isTrue();
        assertThat(IsbnUtils.isValid("0-8044-2957-x")).isTrue();
        assertThat(IsbnUtils.normalize("0-8044-2957-x")).isEqualTo("080442957X");
        assertThat(IsbnUtils.isValid("0306406152")).isTrue();
        assertThat(IsbnUtils.normalize("0306406152")).isEqualTo("0306406152");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9787115428029", "0804429570", "0306406153"})
    @DisplayName("AC-BOOK-01：校验位错误 → isValid=false（D4③ 严格校验）")
    void badChecksum(String isbn) {
        assertThat(IsbnUtils.normalize(isbn)).isNotNull();
        assertThat(IsbnUtils.isValid(isbn)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "", "abcdefghij", "97871154280281", "123456789X1", "X123456789", "12345X7890"})
    @DisplayName("AC-SRCH-01：长度/字符非法 → normalize=null（检索按空结果处理，不报错）")
    void invalidFormat(String isbn) {
        assertThat(IsbnUtils.normalize(isbn)).isNull();
        assertThat(IsbnUtils.isValid(isbn)).isFalse();
    }

    @Test
    @DisplayName("null 输入安全")
    void nullInput() {
        assertThat(IsbnUtils.normalize(null)).isNull();
        assertThat(IsbnUtils.isValid(null)).isFalse();
    }
}