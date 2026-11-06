package com.lender.assistant.conversation.model.dto;

/**
 * 一轮问答的服务端标识集合。conversationId 在首问时新建；其余三项由受理线程一次生成，
 * 并原样通过 SSE meta 回传（ADR-0008 / ADR-0009）。
 */
public record ConversationRoundDTO(String conversationId, String roundId,
                                String userMessageId, String assistantMessageId) {
}
