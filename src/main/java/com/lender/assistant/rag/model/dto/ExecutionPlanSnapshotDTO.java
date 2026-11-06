package com.lender.assistant.rag.model.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code message.execution_plan} 的落库快照。
 *
 * steps[].sourceChunkIds 是该步原始命中，与并集后的对外 sources 不混用。
 */
public record ExecutionPlanSnapshotDTO(boolean degraded, String degradedReason, String provider,
                                    long elapsedMillis, boolean contextTruncated,
                                    List<StepSnapshot> steps) {

    /** 快照的会话级上下文：provider 与耗时不进步骤 */
    public record SnapshotContext(String provider, long elapsedMillis,
                                  boolean contextTruncated, String degradedReason) {
    }

    public record StepSnapshot(String stepId, int order, String title, String tool,
                               Map<String, Object> args, String status, long elapsedMillis,
                               List<Long> sourceChunkIds, String outputSummary, String errorCode) {
    }

    public static ExecutionPlanSnapshotDTO of(ExecutionPlanDTO plan, PlanExecutionResultDTO result,
                                           SnapshotContext context) {
        if (result == null) {
            return new ExecutionPlanSnapshotDTO(plan.degraded(), context.degradedReason(),
                    context.provider(), context.elapsedMillis(), context.contextTruncated(),
                    List.of());
        }
        List<StepSnapshot> steps = result.steps().stream()
                .map(ExecutionPlanSnapshotDTO::toStep)
                .toList();
        return new ExecutionPlanSnapshotDTO(context.degradedReason() != null, context.degradedReason(),
                context.provider(), context.elapsedMillis(), context.contextTruncated(), steps);
    }

    private static StepSnapshot toStep(StepOutcomeDTO outcome) {
        return new StepSnapshot(outcome.stepId(), outcome.order(), outcome.title(), outcome.tool(),
                outcome.args(), outcome.status().name(), outcome.elapsedMillis(),
                outcome.sourceChunkIds(), outcome.outputSummary(), outcome.errorCode());
    }
}
