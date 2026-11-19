package com.example.library.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。username 去首尾空白；password 原样（不做 trim，避免改变凭据）。
 * 口令本身不做策略校验（登录只验证凭据）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "口令不能为空") String password) {
}