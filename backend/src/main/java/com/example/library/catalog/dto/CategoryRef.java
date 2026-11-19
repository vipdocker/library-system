package com.example.library.catalog.dto;

/** 图书摘要/详情中的分类引用（design-v0.1 第 8.3 节 category: {id,name}|null）。 */
public record CategoryRef(Long id, String name) {
}