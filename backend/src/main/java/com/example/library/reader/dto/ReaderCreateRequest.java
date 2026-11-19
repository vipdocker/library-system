package com.example.library.reader.dto;

import com.example.library.common.util.Texts;
import com.example.library.security.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 读者创建请求（design-v0.1 第 8.4 节）：{name, phone?, email?, username, initialPassword}。
 * 同时创建 READER 账号，卡号由系统生成（R%06d）。
 * 紧凑构造器先 trim 再校验（第 8.1 节）；口令不 trim（Texts 约定）。
 */
public record ReaderCreateRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 20) @Pattern(regexp = "^[0-9+\\- ]*$", message = "电话只能包含数字、+、- 和空格") String phone,
        @Size(max = 100) @Email String email,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$",
                message = "用户名必须为 3–32 位字母、数字、下划线、点或连字符") String username,
        @NotBlank @ValidPassword String initialPassword) {

    public ReaderCreateRequest {
        name = Texts.trimToNull(name);
        phone = Texts.trimToNull(phone);
        email = Texts.trimToNull(email);
        username = Texts.trimToNull(username);
    }
}