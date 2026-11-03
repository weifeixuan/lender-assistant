package com.lender.assistant.rag.model;

/**
 * 回放给模型的一条历史消息：role 取 USER / ASSISTANT，content 为落库正文。
 * 只承载展示与模型上下文，不包含来源快照等内容。
 */
public record ChatTurn(String role, String content) {
}
