package com.lender.assistant.conversation.vo.response;

import com.lender.assistant.rag.vo.response.QaSourceResponse;

import java.util.List;

/**
 * 一条历史消息。字段口径与 multi-turn-backend.md §2.5 一致：
 * USER 的 sources/provider/elapsedMillis/error 恒为 null；
 * ASSISTANT 未召回时 sources 为空数组，不是 null。
 */
public record MessageResponse(String messageId, String roundId, String role, String content,
                              String status, List<QaSourceResponse> sources,
                              String provider, Integer elapsedMillis, String error,
                              String createTime) {
}
