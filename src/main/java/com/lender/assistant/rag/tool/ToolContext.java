package com.lender.assistant.rag.tool;

/**
 * 工具执行上下文。
 *
 * @param defaultTopK 计划未显式给 topK 时检索使用的默认条数
 */
public record ToolContext(int defaultTopK) {
}
