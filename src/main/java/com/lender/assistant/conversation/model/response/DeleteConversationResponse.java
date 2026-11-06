package com.lender.assistant.conversation.model.response;

/**
 * 删除会话响应。deleted 为 true 表示本次完成了软删；重复删除不会走到这里，而是返回 404。
 */
public record DeleteConversationResponse(String conversationId, boolean deleted) {
}
