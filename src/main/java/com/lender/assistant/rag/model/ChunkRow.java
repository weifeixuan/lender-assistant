package com.lender.assistant.rag.model;

/**
 * doc_chunk 的待插入行。
 * embedding 传的是 pgvector 文本字面量（"[0.1,...]"）而非 float[]——见 §6.7 与 PgVectorUtils：
 * MyBatis 只能拿到字符串参数，由调用方（DocumentAdminController）统一转换。
 */
public record ChunkRow(String sectionPath, int index, String content, int charCount, String embedding) {
}
