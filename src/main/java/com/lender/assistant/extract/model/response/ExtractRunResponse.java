package com.lender.assistant.extract.model.response;

import com.lender.assistant.extract.model.entity.FieldMappingEntity;

import java.util.List;

/**
 * 字段抽取结果（对应 POST /api/extract/{docId} 的响应体）。
 * mappings 元素直接用 {@link FieldMappingEntity}——其组件名与旧手拼 Map 的键名逐字一致，无需另建载体。
 */
public record ExtractRunResponse(String docId, int count, List<FieldMappingEntity> mappings) {
}
