package com.lender.assistant.conversation.model;

import com.lender.assistant.rag.vo.response.QaSourceResponse;

import java.util.List;

/**
 * 助手消息终态落库入参。
 *
 * 参数超过 4 个收敛为 record（coding-standards §12）：status 覆盖 DONE / FAILED / STOPPED，
 * error 仅在 FAILED 时非空；sources 是生成时的快照（未召回时为空数组）。
 */
public record AssistantMessageCompletion(String conversationId, String roundId, String messageId,
                                         String content, List<QaSourceResponse> sources,
                                         String provider, long elapsedMillis,
                                         String status, String error) {
}
