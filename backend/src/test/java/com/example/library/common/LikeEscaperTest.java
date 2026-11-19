package com.example.library.common;

import com.example.library.common.util.LikeEscaper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** LIKE 字面量转义（支撑 AC-SRCH-01：%、_ 按字面匹配）。 */
class LikeEscaperTest {

    @Test
    @DisplayName("AC-SRCH-01：%、_、反斜杠都按字面转义")
    void escapesSpecialCharacters() {
        assertThat(LikeEscaper.escape("100%")).isEqualTo("100\\%");
        assertThat(LikeEscaper.escape("a_b")).isEqualTo("a\\_b");
        assertThat(LikeEscaper.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(LikeEscaper.escape("%_\\")).isEqualTo("\\%\\_\\\\");
        assertThat(LikeEscaper.escape("普通书名")).isEqualTo("普通书名");
        assertThat(LikeEscaper.escape("")).isEmpty();
        assertThat(LikeEscaper.escape(null)).isNull();
    }
}