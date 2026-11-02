package com.lender.assistant.conversation.model;

/**
 * message 表的一行（读取投影）。
 *
 * sourcesJson 是 JSONB 的文本投影（SQL 侧 sources::text）：MyBatis 不引 JSON 类型处理器，
 * 由上层服务用 Jackson 解析/序列化。用户消息该值为 null；助手未召回时为空数组文本 "[]"。
 */
public record MessageRow(String messageId, String conversationId, String roundId,
                         String role, String content, String status, String sourcesJson,
                         String provider, Integer elapsedMs, String errorMsg,
                         String createTime) {
}
