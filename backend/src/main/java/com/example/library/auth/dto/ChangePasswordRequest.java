package com.example.library.auth.dto;

import com.example.library.security.ValidPassword;
import jakarta.validation.constraints.NotBlank;

/**
 * 修改本人口令请求（design-v0.1 第 8.4 节，D4① / AC-AUTH-10，T14）。
 * 口令原样传输与校验，不做 trim（避免改变凭据，与 LoginRequest 一致）；
 * newPassword 走口令策略（第 7 节：8–64 位，含字母和数字）。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "旧口令不能为空") String oldPassword,
        @NotBlank(message = "新口令不能为空") @ValidPassword String newPassword) {
}