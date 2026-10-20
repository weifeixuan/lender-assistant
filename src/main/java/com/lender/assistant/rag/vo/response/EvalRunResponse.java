package com.lender.assistant.rag.vo.response;

import java.util.List;

/**
 * 检索评估结果（对应 POST /api/admin/eval/run 的响应体）：指标汇总 + 逐条结果 + markdown 报告全文。
 * 组件名与旧 Map 键名逐字一致（前端 / 调优脚本按名取值）。
 */
public record EvalRunResponse(
        String mode,
        int topK,
        int totalItems,
        int evaluatedItems,
        long hits,
        double recallAtK,
        double mrr,
        long avgMillis,
        List<EvalItemResponse> results,
        String markdown) {
}
