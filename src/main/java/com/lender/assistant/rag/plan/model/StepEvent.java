package com.lender.assistant.rag.plan.model;

/** SSE {@code step} 事件的载荷：开始与终态各发一次。 */
public record StepEvent(String stepId, StepStatus status, long elapsedMillis) {
}
