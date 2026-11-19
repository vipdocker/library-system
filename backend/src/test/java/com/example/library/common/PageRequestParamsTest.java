package com.example.library.common;

import java.util.Set;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.web.PageRequestParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分页/排序白名单（design §8.1；支撑各列表 AC 的 400 分支）。 */
class PageRequestParamsTest {

    private static final Set<String> WHITELIST = Set.of("title", "createdAt");
    private static final Sort DEFAULT = Sort.by(Sort.Direction.ASC, "title");

    @Test
    @DisplayName("默认 page=0 size=20 与默认排序")
    void defaults() {
        PageRequest page = PageRequestParams.toPageable(null, null, null, WHITELIST, DEFAULT);
        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getSort()).isEqualTo(DEFAULT);
    }

    @Test
    @DisplayName("合法 sort=createdAt,desc 生效")
    void validSort() {
        PageRequest page = PageRequestParams.toPageable(2, 50, "createdAt,desc", WHITELIST, DEFAULT);
        assertThat(page.getPageNumber()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(50);
        assertThat(page.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("page<0、size 越界、白名单外字段、非法方向、格式错误 → 400 VALIDATION_FAILED")
    void invalidParams() {
        assertValidationFailed(-1, 20, null);
        assertValidationFailed(0, 0, null);
        assertValidationFailed(0, 101, null);
        assertValidationFailed(0, 20, "password,asc");
        assertValidationFailed(0, 20, "title,sideways");
        assertValidationFailed(0, 20, "title");
        assertValidationFailed(0, 20, "title,asc,extra");
    }

    private void assertValidationFailed(Integer page, Integer size, String sort) {
        assertThatThrownBy(() -> PageRequestParams.toPageable(page, size, sort, WHITELIST, DEFAULT))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}