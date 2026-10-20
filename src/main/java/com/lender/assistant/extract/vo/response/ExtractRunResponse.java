package com.lender.assistant.extract.vo.response;

import com.lender.assistant.extract.model.FieldMapping;

import java.util.List;

/**
 * 字段抽取结果（对应 POST /api/extract/{docId} 的响应体）。
 * mappings 元素直接用 {@link FieldMapping}——其组件名与旧手拼 Map 的键名逐字一致，无需另建载体。
 */
public record ExtractRunResponse(String docId, int count, List<FieldMapping> mappings) {
}
