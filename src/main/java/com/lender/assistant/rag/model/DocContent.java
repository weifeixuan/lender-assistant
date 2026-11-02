package com.lender.assistant.rag.model;

import java.util.List;

/**
 * 文档全文（按 chunk 顺序拼接）+ 标题，结构化抽取的输入。
 *
 * lines 保留每个 chunk 的章节路径，供规则抽取按章节分类；
 * text 是拼好的纯文本，只给真实模型的 Prompt 用（模型不解析结构化字段）。
 */
public record DocContent(String docTitle, String text, List<DocChunkLine> lines) {
}
