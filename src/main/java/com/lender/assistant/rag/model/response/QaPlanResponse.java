package com.lender.assistant.rag.model.response;

import com.lender.assistant.rag.model.dto.ExecutionPlanDTO;
import com.lender.assistant.rag.model.dto.PlanStepDTO;

import java.util.List;

/**
 * SSE {@code plan} 事件载荷：只发一次，不暴露 args。
 * 整单弃用时 degraded=true、goal 为空串、steps 为空数组。
 */
public record QaPlanResponse(boolean degraded, String goal, List<QaPlanStepResponse> steps) {

    public static QaPlanResponse of(ExecutionPlanDTO plan) {
        return new QaPlanResponse(plan.degraded(), plan.goal(), plan.steps().stream()
                .map(QaPlanStepResponse::of).toList());
    }

    /** 计划步骤的对外投影（不含 args） */
    public record QaPlanStepResponse(String stepId, int order, String title, String tool) {

        static QaPlanStepResponse of(PlanStepDTO step) {
            return new QaPlanStepResponse(step.stepId(), step.order(), step.title(), step.tool());
        }
    }
}
