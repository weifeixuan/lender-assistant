package com.lender.assistant.conversation.vo.response;

import java.util.List;

/**
 * 历史消息响应（GET /api/conversations/{conversationId}/messages）。
 * messages 扁平返回、按 id 升序；轮次归属由每条消息的 roundId 表达。
 */
public record ConversationMessagesResponse(String conversationId, List<MessageResponse> messages) {
}
