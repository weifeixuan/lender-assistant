package com.lender.assistant.rag.model.dto;

/** 路由结果；reason 只落库/日志，不进 SSE。 */
public record RoutingDecisionDTO(QuestionRoute route, double confidence,
                              String reason, String irrelevantCategory) {
}
