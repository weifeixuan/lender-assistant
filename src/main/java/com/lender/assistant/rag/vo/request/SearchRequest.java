package com.lender.assistant.rag.vo.request;

/**
 * POST /api/admin/documents/search 请求体（原 Map → record，§12）。
 * 组件名 query / topK 与旧 JSON 键名逐字一致。
 */
public record SearchRequest(String query, Integer topK) {
}
