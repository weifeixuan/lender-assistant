package com.lender.assistant.rag.model.response;

/**
 * 文档删除结果（对应 DELETE /api/admin/documents/{docId} 的响应体）。
 * 组件名与旧 Map 键名逐字一致：被删 docId 与删除前统计的切片数。
 */
public record DeleteResponse(String docId, int deletedChunks) {
}
