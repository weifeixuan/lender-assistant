package com.lender.assistant.rag.model;

/** 文档全文（按 chunk 顺序拼接）+ 标题，结构化抽取的输入 */
public record DocContent(String docTitle, String text) {
}
