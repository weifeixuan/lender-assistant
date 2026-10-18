package com.lender.assistant.rag.model;

/** 关键词召回命中（hits = 命中 token 数，越大越相关）。id/hits 用包装类型，原因见 DocSummary 注释（§6.6） */
public record KeywordHit(Long id, String docId, String docTitle, String sectionPath,
                         String content, Integer hits) {
}
