package com.lender.assistant.rag.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.plan.PlanAndExecuteService;
import com.lender.assistant.rag.plan.model.ExecutionPlan;
import com.lender.assistant.rag.routing.QuestionRoutingService;
import com.lender.assistant.rag.routing.model.RoutingDecision;
import com.lender.assistant.rag.routing.model.RoutingRequest;
import com.lender.assistant.rag.service.RoutingEvalService;
import com.lender.assistant.rag.vo.response.RoutingEvalItemResponse;
import com.lender.assistant.rag.vo.response.RoutingEvalResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由与计划离线评估实现：评估集固定、指标口径固定、阈值只读不调。
 * 计划相关指标在 mock provider 下必然为 0——这是诚实结果，不做任何伪造。
 */
@Service
public class RoutingEvalServiceImpl implements RoutingEvalService {

    private static final String DEFAULT_SET = "eval/routing-evalset.json";
    private static final String PLAN = "PLAN";
    private static final String COREFERENCE = "COREFERENCE";
    private static final String KB_SEARCH = "kb.search";

    private final QuestionRoutingService routingService;
    private final PlanAndExecuteService planningService;
    private final ObjectMapper objectMapper;
    private final String evalResource;

    @Value("${routing.confidence-threshold:0.6}")
    private double threshold;

    @Autowired
    public RoutingEvalServiceImpl(QuestionRoutingService routingService,
                                  PlanAndExecuteService planningService,
                                  ObjectMapper objectMapper) {
        this(routingService, planningService, objectMapper, DEFAULT_SET);
    }

    /** 测试可指定更小的评估集 */
    public RoutingEvalServiceImpl(QuestionRoutingService routingService,
                                  PlanAndExecuteService planningService,
                                  ObjectMapper objectMapper, String evalResource) {
        this.routingService = routingService;
        this.planningService = planningService;
        this.objectMapper = objectMapper;
        this.evalResource = evalResource;
    }

    @Override
    public RoutingEvalResponse run() throws Exception {
        List<RoutingEvalItemResponse> results = new ArrayList<>();
        for (EvalItem item : load()) {
            results.add(evaluate(item));
        }
        return summarize(results);
    }

    private RoutingEvalItemResponse evaluate(EvalItem item) {
        RoutingDecision decision = routingService.route(
                new RoutingRequest(item.question(), item.previousQuestion(), null, ""));
        boolean correct = item.expectedRoute().equals(decision.route().name());
        boolean planValid = false;
        int stepCount = 0;
        boolean coreferencePass = false;
        if (PLAN.equals(item.expectedRoute())) {
            ExecutionPlan plan = planningService.plan(item.question(), item.previousQuestion());
            planValid = !plan.degraded();
            stepCount = plan.steps().size();
            coreferencePass = coreferencePass(item, plan);
        }
        return new RoutingEvalItemResponse(item.id(), item.category(), item.question(),
                item.expectedRoute(), decision.route().name(), decision.confidence(), correct,
                planValid, stepCount, coreferencePass, item.note());
    }

    /** 指代型追问的验收动作：合法计划且步骤工具全部在允许清单内 */
    private static boolean coreferencePass(EvalItem item, ExecutionPlan plan) {
        if (!COREFERENCE.equals(item.category()) || plan.degraded()) {
            return false;
        }
        List<String> allowed = item.allowedTools() == null ? List.of(KB_SEARCH) : item.allowedTools();
        return plan.steps().stream().allMatch(step -> allowed.contains(step.tool()));
    }

    private RoutingEvalResponse summarize(List<RoutingEvalItemResponse> results) {
        int total = results.size();
        int correct = (int) results.stream().filter(RoutingEvalItemResponse::correct).count();
        int lowConfidence = (int) results.stream().filter(r -> r.confidence() < threshold).count();
        List<RoutingEvalItemResponse> planItems = results.stream()
                .filter(r -> PLAN.equals(r.expectedRoute())).toList();
        int validPlans = (int) planItems.stream().filter(RoutingEvalItemResponse::planValid).count();
        double avgSteps = planItems.stream().filter(RoutingEvalItemResponse::planValid)
                .mapToInt(RoutingEvalItemResponse::stepCount).average().orElse(0);
        List<RoutingEvalItemResponse> coreference = results.stream()
                .filter(r -> COREFERENCE.equals(r.category())).toList();
        double coreferencePassRate = coreference.isEmpty() ? 0
                : (double) coreference.stream().filter(RoutingEvalItemResponse::coreferencePass).count()
                / coreference.size();
        return new RoutingEvalResponse(String.format("%.2f", threshold), total,
                ratio(correct, total), ratio(lowConfidence, total), planItems.size(),
                ratio(validPlans, planItems.size()), avgSteps, coreference.size(),
                coreferencePassRate, results, buildMarkdown(results, planItems.size(),
                ratio(correct, total), ratio(lowConfidence, total), ratio(validPlans,
                        planItems.size())));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private String buildMarkdown(List<RoutingEvalItemResponse> results, int planItems,
                                 double routeAccuracy, double lowConfidenceRatio,
                                 double planValidRate) {
        StringBuilder md = new StringBuilder("# 路由与计划评估报告\n\n");
        md.append("- 评估集条数：").append(results.size()).append('\n');
        md.append("- 当前阈值：").append(String.format("%.2f", threshold)).append("\n\n");
        md.append("| 指标 | 数值 |\n| --- | --- |\n");
        md.append("| 路由准确率 | ").append(formatPercent(routeAccuracy)).append(" |\n");
        md.append("| 低置信占比 | ").append(formatPercent(lowConfidenceRatio)).append(" |\n");
        md.append("| 期望 PLAN 条数 | ").append(planItems).append(" |\n");
        md.append("| 计划合法率 | ").append(formatPercent(planValidRate)).append(" |\n\n");
        md.append("> 计划合法率与指代验收依赖真实计划模型；mock provider 下计划恒被弃用，"
                + "指标为 0 属预期。阈值校准需要真实模型与真实标注集，本报告不提供校准结论。\n");
        return md.toString();
    }

    private static String formatPercent(double value) {
        return String.format("%.2f%%", value * 100);
    }

    private List<EvalItem> load() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(evalResource)) {
            if (input == null) {
                throw new IllegalStateException("评估集不存在：" + evalResource);
            }
            return objectMapper.readValue(input, EvalSet.class).items();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvalSet(List<EvalItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvalItem(int id, String category, String question, String previousQuestion,
                            String expectedRoute, List<String> allowedTools, String note) {
    }
}
