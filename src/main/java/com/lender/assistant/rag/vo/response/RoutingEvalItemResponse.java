package com.lender.assistant.rag.vo.response;

/**
 * 路由评估的单条结果。
 *
 * planValid / stepCount 只对「期望 PLAN」与指代型追问有意义；
 * 非 PLAN 条目的 planValid 为 false、stepCount 为 0，不参与计划指标。
 *
 * @param coreferencePass 指代型追问专属：计划是否只用了允许的工具
 */
public record RoutingEvalItemResponse(int id, String category, String question,
                                      String expectedRoute, String actualRoute, double confidence,
                                      boolean correct, boolean planValid, int stepCount,
                                      boolean coreferencePass, String note) {
}
