package com.lender.assistant.rag.vo.response;

import java.util.List;

/**
 * 文档切片分页出参：page 从 1 开始，total 为文档全部切片数，items 为当前页。
 * 页码/页大小已在 Service 归一，返回的是实际生效值，前端据此算总页数。
 */
public record ChunkPageResponse(String docId, int page, int size, long total,
                                List<ChunkItemResponse> items) {
}
