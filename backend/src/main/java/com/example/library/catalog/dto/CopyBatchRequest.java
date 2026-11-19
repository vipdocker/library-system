package com.example.library.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 追加副本请求（design-v0.1 第 8.4 节）：{count: 1..50}。 */
public record CopyBatchRequest(@NotNull @Min(1) @Max(50) Integer count) {
}