package com.lender.assistant.conversation.model;

/**
 * 助手消息 sources 的 JSONB 快照元素。
 *
 * 字段与 rag 侧 {@code QaSourceResponse}（SSE sources 载荷）逐字段同构：列名、可空性与
 * JSON 序列化结果一致，因此历史接口与 SSE 契约不变。
 * 单独定义是为了让 conversation 包不依赖 rag 包，断开 conversation ↔ rag 的包级依赖环。
 */
public record QaSourceSnapshot(Integer idx, String docTitle, String sectionPath,
                               Long chunkId, String content, Double vectorDistance,
                               Integer keywordHits, Boolean bothChannels, String rrfScore) {
}
