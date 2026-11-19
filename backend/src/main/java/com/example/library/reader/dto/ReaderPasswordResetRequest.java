package com.example.library.reader.dto;

import com.example.library.security.ValidPassword;
import jakarta.validation.constraints.NotBlank;

/**
 * 管理员重置读者口令请求（design-v0.1 第 8.4 节）：{newPassword}。
 * 口令策略 8–64 位含字母和数字（第 7 节，@ValidPassword）；不 trim。
 */
public record ReaderPasswordResetRequest(@NotBlank @ValidPassword String newPassword) {
}