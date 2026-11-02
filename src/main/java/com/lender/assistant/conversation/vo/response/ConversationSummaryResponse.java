package com.lender.assistant.conversation.vo.response;

/**
 * 会话列表条目（GET /api/conversations）。
 *
 * 时间由 SQL 侧格式化为 YYYY-MM-DD HH:mm:ss，前端按本地时间展示。
 */
public record ConversationSummaryResponse(String conversationId, String title,
                                          String createTime, String updateTime) {
}
