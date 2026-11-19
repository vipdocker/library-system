package com.example.library.reader.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 读者更新请求（design-v0.1 第 8.4 节）：{name, phone?, email?, status}。
 * status 只能为 ACTIVE / DISABLED，服务层解析（非法值 → 400 + fieldErrors[status]，与 T04 副本状态写法一致）；
 * 停用时同一事务内禁用账号（reader.status 是唯一事实来源，第 6 节）。
 */
public record ReaderUpdateRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 20) @Pattern(regexp = "^[0-9+\\- ]*$", message = "电话只能包含数字、+、- 和空格") String phone,
        @Size(max = 100) @Email String email,
        @NotBlank String status) {

    public ReaderUpdateRequest {
        name = Texts.trimToNull(name);
        phone = Texts.trimToNull(phone);
        email = Texts.trimToNull(email);
        status = Texts.trimToNull(status);
    }
}