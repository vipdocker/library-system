package com.example.library.catalog.dto;

import com.example.library.common.util.Texts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分类创建/更新请求（design-v0.1 第 8.4 节）：{name, description?}。
 * 紧凑构造器先 trim（第 8.1 节：去首尾空白；可选字段空白按 null），再做 Bean Validation，
 * 保证长度校验作用于 trim 后的值。
 */
public record CategoryRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {

    public CategoryRequest {
        name = Texts.trimToNull(name);
        description = Texts.trimToNull(description);
    }
}