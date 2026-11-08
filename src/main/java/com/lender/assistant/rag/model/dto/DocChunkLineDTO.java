package com.lender.assistant.rag.model.dto;

/** 单条切片行（不含 embedding）：loadDocContent 按 chunk_index 取全文用 */
public record DocChunkLineDTO(String docTitle, String sectionPath, String content) {
}
