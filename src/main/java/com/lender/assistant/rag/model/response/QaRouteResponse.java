package com.lender.assistant.rag.model.response;

/** SSE route 事件载荷：reason 与 irrelevantCategory 只落库，不下发。 */
public record QaRouteResponse(String route, double confidence) {
}
