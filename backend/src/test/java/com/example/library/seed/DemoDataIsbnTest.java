package com.example.library.seed;

import com.example.library.common.util.IsbnUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DOD-06 / design §11：演示图书的 ISBN 必须全部通过校验位检查（独立于种子实现的断言）。 */
class DemoDataIsbnTest {

    @Test
    @DisplayName("DOD-06：12 本演示图书 ISBN 互不相同且全部通过严格校验")
    void allDemoIsbnsValid() {
        assertThat(DemoDataLoader.DEMO_BOOKS).hasSize(12);
        assertThat(DemoDataLoader.DEMO_BOOKS).allSatisfy(book ->
                assertThat(IsbnUtils.isValid(book.isbn()))
                        .as("演示 ISBN %s（%s）必须通过校验位检查", book.isbn(), book.title())
                        .isTrue());
        assertThat(DemoDataLoader.DEMO_BOOKS.stream().map(DemoDataLoader.DemoBook::isbn).distinct().count())
                .isEqualTo(12);
    }
}