package com.lender.assistant.rag.vo.response;

/**
 * 单条评估结果（{@link EvalRunResponse#results()} 的元素）。
 * 它随 run() 返回值对外可见，属跨层数据载体（§1）。组件名与旧键名逐字一致。
 */
public record EvalItemResponse(
        int id,
        String type,
        String question,
        boolean hit,
        int firstHitRank,
        int recallTopK,
        long millis,
        String note) {
}
