package com.lender.assistant.rag.model.response;

import java.util.List;

/**
 * 路由与计划离线评估汇总。
 *
 * 指标口径：
 * - routeAccuracy：路由预测与标注一致的比例；
 * - lowConfidenceRatio：置信度低于当前阈值的比例（阈值只读当前配置，不在本接口调优）；
 * - planValidRate：期望 PLAN 的条目中，整单校验通过的比例；
 * - avgSteps：合法计划的平均步骤数；
 * - coreferencePassRate：指代型追问中，计划未引入无关工具的比例。
 *
 * 诚实声明：计划合法率与指代验收依赖真实计划模型；mock provider 下计划恒被弃用，
 * 指标为 0 是预期结果，不能作为阈值校准依据。
 */
public record RoutingEvalResponse(String threshold, int total, double routeAccuracy,
                                  double lowConfidenceRatio, int planItems, double planValidRate,
                                  double avgSteps, int coreferenceItems, double coreferencePassRate,
                                  List<RoutingEvalItemResponse> results, String markdown) {
}
