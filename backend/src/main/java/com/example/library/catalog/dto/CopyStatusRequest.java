package com.example.library.catalog.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.NotBlank;

/**
 * 副本状态修改请求（design-v0.1 第 8.4 节）：{status: "AVAILABLE"|"UNAVAILABLE"}。
 * 用 String 接收并在服务层校验取值：传入 BORROWED 或非法值时返回 400 + fieldErrors，
 * 而不是 Jackson 反序列化失败的笼统 400。
 */
public record CopyStatusRequest(@NotBlank String status) {

    public CopyStatusRequest {
        status = Texts.trimToNull(status);
    }
}