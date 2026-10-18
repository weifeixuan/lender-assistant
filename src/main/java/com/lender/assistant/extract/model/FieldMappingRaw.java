package com.lender.assistant.extract.model;

/**
 * field_mapping 的原始行（无内部字段名）：两个用途共用同一 record —
 * - 写入：saveMappings 的批量 upsert 参数（docId + 抽取出的 5 个字段）；
 * - 取数：草稿生成只为拿字段值，内部字段名由 Service 用 InternalFieldCatalog 补齐
 *   （§6.11：Mapper 只取数，这类业务判定留在 Service）。
 * confidence 用包装类型 Double（原因见 AGENTS.md §6.6，MyBatis 构造映射不认 primitive）；
 * 该列可空，取数 SQL 用 COALESCE(confidence, 0) 兜底为 0，与旧实现一致。
 */
public record FieldMappingRaw(String docId, String internalFieldId, String lenderFieldName,
                              String lenderValue, String sourceQuote, Double confidence) {
}
