package com.lender.assistant.rag.model;

/** 向量召回命中（distance = 余弦距离，越小越相似） */
public record VectorHit(long id, String docId, String docTitle, String sectionPath,
                        String content, double distance) {
}
