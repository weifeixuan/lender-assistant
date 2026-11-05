package com.lender.assistant.rag.plan.model;

import java.util.List;

/**
 * 一轮计划执行汇总。
 *
 * @param steps                按 order 排列的逐步结果
 * @param budgetExhausted      是否因剩余预算不足而跳过步骤（对应 BUDGET_EXHAUSTED）
 * @param remainingBudgetMillis 执行结束时剩余预算
 */
public record PlanExecutionResult(List<StepOutcome> steps, boolean budgetExhausted,
                                  long remainingBudgetMillis) {
}
