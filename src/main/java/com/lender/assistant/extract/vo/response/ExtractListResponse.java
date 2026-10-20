package com.lender.assistant.extract.vo.response;

import com.lender.assistant.extract.model.FieldMappingRow;

import java.util.List;

/**
 * 某文档当前映射表（对应 GET /api/extract/{docId} 的响应体）。
 * mappings 元素为 {@link FieldMappingRow}（带 PENDING/CONFIRMED/CORRECTED 状态），键名与旧契约一致。
 */
public record ExtractListResponse(String docId, List<FieldMappingRow> mappings) {
}
