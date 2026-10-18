package com.lender.assistant.rag.model;

/** 关键词召回命中（hits = 命中 token 数，越大越相关） */
public record KeywordHit(long id, String docId, String docTitle, String sectionPath,
                         String content, int hits) {
}
