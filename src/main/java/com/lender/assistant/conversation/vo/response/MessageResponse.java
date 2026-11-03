package com.lender.assistant.conversation.vo.response;

import com.lender.assistant.conversation.model.QaSourceSnapshot;

import java.util.List;

/**
 * 一条历史消息。字段口径与 multi-turn-backend.md §2.5 一致：
 * USER 的 sources/provider/elapsedMillis/error 恒为 null；
 * ASSISTANT 未召回时 sources 为空数组，不是 null。
 * sources 用会话侧的 QaSourceSnapshot：JSON 字段与 SSE 的 QaSourceResponse 同构。
 */
public record MessageResponse(String messageId, String roundId, String role, String content,
                              String status, List<QaSourceSnapshot> sources,
                              String provider, Integer elapsedMillis, String error,
                              String createTime) {
}
