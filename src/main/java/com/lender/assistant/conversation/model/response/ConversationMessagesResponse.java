package com.lender.assistant.conversation.model.response;

import java.util.List;

/**
 * 历史消息响应（GET /api/conversations/{conversationId}/messages）。
 * messages 扁平返回、按 id 升序；轮次归属由每条消息的 roundId 表达。
 * summary / summaryBoundaryRoundId 为滚动摘要元信息：未生成过摘要的会话两字段均为 null，
 * 前端以 summary 非空作为提示条显示条件（PRD §5.1）。
 */
public record ConversationMessagesResponse(String conversationId, String summary,
                                           String summaryBoundaryRoundId,
                                           List<MessageResponse> messages) {
}
