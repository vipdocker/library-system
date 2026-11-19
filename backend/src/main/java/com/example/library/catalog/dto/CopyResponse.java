package com.example.library.catalog.dto;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import com.example.library.domain.entity.BookCopy;

/**
 * Copy（design-v0.1 第 8.3 节）：{ id, bookId, barcode, status, createdAt }。
 * createdAt 显式格式化为 ISO-8601 UTC 字符串（第 8.1 节），截断到毫秒与存储精度一致。
 */
public record CopyResponse(Long id, Long bookId, String barcode, String status, String createdAt) {

    public static CopyResponse from(BookCopy copy) {
        return new CopyResponse(copy.getId(), copy.getBook().getId(), copy.getBarcode(), copy.getStatus().name(),
                DateTimeFormatter.ISO_INSTANT.format(copy.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)));
    }
}