package com.lender.assistant.rag.vo.response;

/**
 * 文档上传入库结果（对应 POST /api/admin/documents 的响应体）。
 *
 * 组件名与旧 Map 手拼的键名逐字一致（前端按名取值）：
 * 解析出的 block 数、切分后的 chunk 数、平均 chunk 字符数、各阶段耗时、向量化 provider。
 */
public record UploadResponse(
        String docId,
        int blocks,
        int chunkCount,
        double avgChunkChars,
        IngestMillisResponse millis,
        String embeddingProvider) {
}
