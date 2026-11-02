package com.lender.assistant.conversation.model;

/**
 * conversation 表的一行（读取投影）。
 *
 * 时间用 String 承接 SQL 侧 TO_CHAR 的结果，避免把时区/序列化歧义带进领域模型；
 * 数值组件若新增必须用包装类型（ADR-0004 / §6.6）。
 */
public record ConversationRow(String conversationId, String userId, String title,
                              String status, String createTime, String updateTime) {
}
