package com.lender.assistant.conversation.model.dto;

/**
 * 插入一条消息所需的字段集合。
 *
 * 参数超过 4 个，按编码规范 §6.6 收敛为 record；Mapper 以单参数对象接收，
 * XML 直接引用组件名。sourcesJson 为 JSON 文本，可为 null（用户消息）。
 */
public record MessageInsertDTO(String conversationId, String roundId, String messageId,
                            String role, String content, String status, String sourcesJson,
                            String provider, Integer elapsedMs, String errorMsg) {
}
