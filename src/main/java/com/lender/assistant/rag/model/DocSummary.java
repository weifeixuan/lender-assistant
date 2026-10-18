package com.lender.assistant.rag.model;

/** 已入库文档概览（doc_chunk 按 doc_id 聚合） */
public record DocSummary(String docId, String docTitle, int chunkCount) {
}
