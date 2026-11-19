package com.example.library.common.web;

import java.util.Locale;
import java.util.Set;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 分页/排序参数解析与白名单校验（design-v0.1 第 8.1 节）。
 * page ≥0 默认 0；size 1–100 默认 20；sort=field,asc|desc，字段必须在接口白名单内；
 * 越界或非法 → 400 VALIDATION_FAILED。
 */
public final class PageRequestParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequestParams() {
    }

    public static PageRequest toPageable(Integer page, Integer size, String sort,
                                         Set<String> sortWhitelist, Sort defaultSort) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "page 不能小于 0");
        }
        int s = size == null ? DEFAULT_SIZE : size;
        if (s < 1 || s > MAX_SIZE) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "size 必须在 1–100 之间");
        }
        Sort resolved = defaultSort;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.trim().split(",");
            if (parts.length != 2) {
                throw ApiException.of(ErrorCode.VALIDATION_FAILED, "sort 格式必须为 field,asc|desc");
            }
            String field = parts[0].trim();
            String dir = parts[1].trim().toLowerCase(Locale.ROOT);
            if (!sortWhitelist.contains(field)) {
                throw ApiException.of(ErrorCode.VALIDATION_FAILED, "不支持的排序字段: " + field);
            }
            if (!dir.equals("asc") && !dir.equals("desc")) {
                throw ApiException.of(ErrorCode.VALIDATION_FAILED, "排序方向必须为 asc 或 desc");
            }
            resolved = Sort.by(dir.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, field);
        }
        return PageRequest.of(p, s, resolved);
    }
}