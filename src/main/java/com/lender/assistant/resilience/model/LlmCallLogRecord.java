package com.lender.assistant.resilience.model;

/**
 * 一次模型调用的留痕行（llm_call_log）。
 *
 * 为什么用 record 而不是 7 个方法参数：§2.8 规定方法参数 ≤ 4 个；
 * 留痕字段天然是一组整体，收敛为 record 同时让 Mapper 传参稳定。
 * token 可为 null——Mock 调用没有真实计量，诚实留空比编数字好。
 */
public record LlmCallLogRecord(
        String scene,
        String provider,
        Integer durationMs,
        Integer promptTokens,
        Integer completionTokens,
        String status,
        String errorMsg) {
}
