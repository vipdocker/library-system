package com.example.library.reader.dto;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import com.example.library.domain.entity.Reader;

/**
 * Reader（design-v0.1 第 8.3 节）：
 * { id, cardNo, name, phone|null, email|null, status, username, activeLoanCount, overdueLoanCount, createdAt }。
 * 不含任何口令或哈希字段（AC-AUTH-08）。createdAt 为 ISO-8601 UTC 字符串，截断到毫秒与存储精度一致。
 */
public record ReaderResponse(Long id, String cardNo, String name, String phone, String email, String status,
                             String username, long activeLoanCount, long overdueLoanCount, String createdAt) {

    public static ReaderResponse from(Reader reader, String username, long activeLoanCount, long overdueLoanCount) {
        return new ReaderResponse(reader.getId(), reader.getCardNo(), reader.getName(), reader.getPhone(),
                reader.getEmail(), reader.getStatus().name(), username, activeLoanCount, overdueLoanCount,
                DateTimeFormatter.ISO_INSTANT.format(reader.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)));
    }
}