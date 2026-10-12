package com.lender.assistant.extract;

/**
 * 一条字段映射：内部标准字段 ↔ 资方侧的对应信息。
 *
 * lenderFieldName：资方文档里这个信息的"字段名/标签"（如 merNo、网关地址）；
 * lenderValue：资方侧的具体取值（如 https://fake-xxx.example.com/gateway）；
 * sourceQuote：该值在文档中的原文出处行——引用溯源要求，抽取必须可回查；
 * confidence：置信度 (0,1]，规则精确命中 0.9 / 语义定位 0.7，前端按它排序提示人工复核。
 */
public record FieldMapping(
        String internalFieldId,
        String internalFieldName,
        String lenderFieldName,
        String lenderValue,
        String sourceQuote,
        double confidence) {
}
