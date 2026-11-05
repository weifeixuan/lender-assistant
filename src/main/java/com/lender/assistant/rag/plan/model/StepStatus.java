package com.lender.assistant.rag.plan.model;

/**
 * 步骤状态机：PENDING → RUNNING → DONE | DEGRADED | FAILED | SKIPPED。
 * 失败与降级都不重跑、不重新计划，只记录结果后继续后续步骤。
 */
public enum StepStatus {
    PENDING, RUNNING, DONE, DEGRADED, FAILED, SKIPPED
}
