package com.lender.assistant.rag.model;

/**
 * 切片分页查询的行（不含 embedding：1024 维向量会让响应从 KB 级膨胀到 MB 级）。
 * id/index/charCount 用包装类型，原因见 DocSummary 注释（§6.6 MyBatis &lt;constructor&gt; 映射）。
 */
public record ChunkItemRow(Long chunkId, Integer index, String sectionPath,
                           Integer charCount, String content) {
}
