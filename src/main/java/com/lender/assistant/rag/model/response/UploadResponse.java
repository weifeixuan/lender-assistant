package com.lender.assistant.rag.model.response;

/**
 * 文档上传入库结果（对应 POST /api/admin/documents 的响应体）。
 *
 * 组件名与旧 Map 手拼的键名逐字一致（前端按名取值）：
 * 解析出的 block 数、切分后的 ChunkDTO 数、平均 ChunkDTO 字符数、各阶段耗时、向量化 provider。
 *
 * docId：判重命中时返回既有内容的 docId，可能不等于请求参数里的 docId；
 * 该 docId 对应的既有文档不会被本次调用创建或覆盖。
 */
public record UploadResponse(
        String docId,
        int blocks,
        int chunkCount,
        double avgChunkChars,
        IngestMillisResponse millis,
        String embeddingProvider) {
}
