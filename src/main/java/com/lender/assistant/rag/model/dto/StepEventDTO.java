package com.lender.assistant.rag.model.dto;

/** SSE {@code step} 事件的载荷：开始与终态各发一次。 */
public record StepEventDTO(String stepId, StepStatus status, long elapsedMillis) {
}
