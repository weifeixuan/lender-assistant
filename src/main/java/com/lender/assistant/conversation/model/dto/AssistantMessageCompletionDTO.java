package com.lender.assistant.conversation.model.dto;

import java.util.List;

/**
 * 助手消息终态落库入参。
 *
 * 参数超过 4 个收敛为 record（coding-standards §12）：status 覆盖 DONE / FAILED / STOPPED，
 * error 仅在 FAILED 时非空；sources 是生成时的快照（未召回时为空数组）。
 * routeJson / executionPlanJson 为已序列化的 JSON 文本，非 PLAN 轮或未路由时为 null。
 */
public record AssistantMessageCompletionDTO(String conversationId, String roundId, String messageId,
                                         String content, List<QaSourceSnapshotDTO> sources,
                                         String provider, long elapsedMillis,
                                         String status, String error,
                                         String routeJson, String executionPlanJson) {
}
