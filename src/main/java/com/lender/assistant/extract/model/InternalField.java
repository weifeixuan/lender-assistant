package com.lender.assistant.extract.model;

/**
 * 内部标准字段：映射表"我方一侧"的字段定义。
 *
 * 为什么用 record 而不是枚举：字段集要出现在 JSON Schema 文本、
 * 前端下拉、数据库字段对照里，record + 静态目录比 enum 更容易序列化展示。
 */
public record InternalField(String id, String name, String description) {
}
