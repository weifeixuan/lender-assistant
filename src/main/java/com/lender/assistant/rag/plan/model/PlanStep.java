package com.lender.assistant.rag.plan.model;

import java.util.Map;

/**
 * 计划中的单个步骤：一次确定性工具调用。
 *
 * @param stepId 步骤标识，固定为 s1–sN
 * @param order  执行顺序，从 1 连续递增
 * @param title  给人看的步骤名
 * @param tool   工具名，必须命中 {@code PlanToolRegistry} 白名单
 * @param args   计划期字面量参数（不含运行时占位符）
 */
public record PlanStep(String stepId, int order, String title, String tool,
                       Map<String, Object> args) {
}
