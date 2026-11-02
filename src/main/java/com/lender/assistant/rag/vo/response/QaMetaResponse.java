package com.lender.assistant.rag.vo.response;

/**
 * SSE "meta" 事件载荷：本轮的四项标识。
 *
 * 首问走懒创建，前端拿不到会话 id —— meta 是前端建立会话、把后续增量回答归到该轮的唯一入口。
 * 四项都是 UUID，与落库的 conversation / message 行一一对应。
 */
public record QaMetaResponse(String conversationId, String roundId,
                             String userMessageId, String assistantMessageId) {
}
