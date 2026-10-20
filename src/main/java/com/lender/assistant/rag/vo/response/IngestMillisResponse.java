package com.lender.assistant.rag.vo.response;

/**
 * 文档入库各阶段耗时（毫秒）：解析 / 切分 / 向量化 / 写库。
 * 作为 {@link UploadResponse} 的内嵌对象，键名 parse / split / embed / store 与前端逐字一致。
 */
public record IngestMillisResponse(long parse, long split, long embed, long store) {
}
