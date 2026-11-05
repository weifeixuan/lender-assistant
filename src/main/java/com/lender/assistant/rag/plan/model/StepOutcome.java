package com.lender.assistant.rag.plan.model;

import com.lender.assistant.rag.vo.response.SearchItemResponse;

import java.util.List;
import java.util.Map;

/**
 * 单步执行结果，也是 {@code execution_plan.steps[]} 的落库形状。
 *
 * sourceChunkIds 只记录该步原始命中，不与跨步并集混淆（并集在来源汇总时生成）；
 * sources 是同一批命中的完整条目，供并集去重后重建对外 sources。
 *
 * @param args            计划期字面量参数
 * @param status          终态（RUNNING 只存在于事件，不落快照）
 * @param elapsedMillis   单步耗时
 * @param sourceChunkIds  原始命中块 id，零命中为空
 * @param sources         原始命中完整条目，mock 工具恒为空
 * @param outputSummary   给合成用的文本产物（已截断）
 * @param errorCode       失败/降级时的固定错误码，成功为 null
 */
public record StepOutcome(String stepId, int order, String title, String tool,
                          Map<String, Object> args, StepStatus status, long elapsedMillis,
                          List<Long> sourceChunkIds, List<SearchItemResponse> sources,
                          String outputSummary, String errorCode) {
}
