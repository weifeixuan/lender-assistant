package com.lender.assistant.rag.model.dto;

import java.util.List;

/**
 * 一轮的计划产出。整单原子校验：任何一项不合法都整体弃用，
 * 调用方据此退回单次检索的 KNOWLEDGE 链路（不做部分执行）。
 *
 * @param degraded       是否已弃用
 * @param degradedReason 弃用原因（{@code PLAN_INVALID}），未弃用为 null
 * @param goal           本轮目标；弃用时为空串
 * @param steps          有序步骤；弃用时为空列表
 */
public record ExecutionPlanDTO(boolean degraded, String degradedReason, String goal,
                            List<PlanStepDTO> steps) {

    public static final String PLAN_INVALID = "PLAN_INVALID";

    /** 弃用结果：目标清空、步骤清空，reason 固定 PLAN_INVALID */
    public static ExecutionPlanDTO invalid() {
        return new ExecutionPlanDTO(true, PLAN_INVALID, "", List.of());
    }

    public static ExecutionPlanDTO valid(String goal, List<PlanStepDTO> steps) {
        return new ExecutionPlanDTO(false, null, goal, List.copyOf(steps));
    }
}
