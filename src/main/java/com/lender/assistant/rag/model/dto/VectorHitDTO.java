package com.lender.assistant.rag.model.dto;

/** 向量召回命中（distance = 余弦距离，越小越相似）。id/distance 用包装类型，原因见 DocSummaryDTO 注释（§6.6） */
public record VectorHitDTO(Long id, String docId, String docTitle, String sectionPath,
                        String content, Double distance, Integer chunkIndex) {
}
