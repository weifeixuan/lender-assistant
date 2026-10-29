package com.lender.assistant.rag.model;

/**
 * 已入库文档概览（doc_chunk 按 doc_id 聚合）：前端文档列表要展示的原始文件名、
 * 总字符数与最近入库时间都来自这里。
 *
 * chunkCount / charCountTotal 用包装类型而非 primitive：MyBatis 的 <constructor> 结果映射会把
 * javaType 别名 "int"/"long" 解析为包装类，映射时传入的是 boxed 值，primitive 参数会找不到构造器
 * （NoSuchMethodException）。详见 AGENTS.md §6.6。
 *
 * updateTime 用 String（SQL 侧 TO_CHAR 格式化）：避免给前端引入时区/序列化歧义。
 */
public record DocSummary(String docId, String docTitle, String sourcePath,
                         Integer chunkCount, Long charCountTotal, String updateTime) {
}
