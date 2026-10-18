package com.lender.assistant.extract.model;

/**
 * field_mapping 查询行：GET /api/extract/{docId} 的原样返回元素。
 *
 * 组件名与旧 FieldMappingStore.listMappings 的 Map 键名逐字相同——这是刻意的，
 * 保证 JSON 契约（前端渲染映射表）在迁移后零变化。
 * lenderFieldName / lenderValue / sourceQuote 的 null→"" 兜底由 XML 的 COALESCE 完成。
 */
public record FieldMappingRow(Long id, String docId, String internalFieldId, String lenderFieldName,
                             String lenderValue, String sourceQuote, Double confidence, String status) {
}
