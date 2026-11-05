package com.lender.assistant.rag.routing.model;

/** 路由结果；reason 只落库/日志，不进 SSE。 */
public record RoutingDecision(QuestionRoute route, double confidence,
                              String reason, String irrelevantCategory) {
}
