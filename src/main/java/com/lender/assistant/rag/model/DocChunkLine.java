package com.lender.assistant.rag.model;

/** 单条 chunk 行（不含 embedding）：loadDocContent 按 chunk_index 取全文用 */
public record DocChunkLine(String docTitle, String sectionPath, String content) {
}
