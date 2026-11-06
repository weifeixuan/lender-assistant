package com.lender.assistant.rag.model.response;

import com.lender.assistant.rag.model.dto.StepEventDTO;

/** SSE {@code step} 事件载荷：开始（RUNNING）与终态各一次。 */
public record QaStepResponse(String stepId, String status, long elapsedMillis) {

    public static QaStepResponse of(StepEventDTO event) {
        return new QaStepResponse(event.stepId(), event.status().name(), event.elapsedMillis());
    }
}
