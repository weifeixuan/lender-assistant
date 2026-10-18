package com.lender.assistant.rag.model;

/**
 * 已入库文档概览（doc_chunk 按 doc_id 聚合）。
 *
 * chunkCount 用包装类型 Integer 而非 primitive int：MyBatis 的 <constructor> 结果映射会把
 * javaType 别名 "int" 解析为 Integer，映射时传入的是 boxed 值，primitive 参数会找不到构造器
 * （NoSuchMethodException）。详见 AGENTS.md §6.6。
 */
public record DocSummary(String docId, String docTitle, Integer chunkCount) {
}
