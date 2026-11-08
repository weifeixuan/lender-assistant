package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.dto.StepOutcomeDTO;
import com.lender.assistant.rag.model.response.SearchItemResponse;

import java.util.List;
import java.util.Map;

/**
 * PLAN 轮合成上下文：只拼「目标 + 步骤产物 + 当前问题」，不重复完整对话历史。
 *
 * 引用编号归编排层：kb.search 步骤只给命中条数与全局编号，正文统一放到末尾的
 * 「召回来源」清单里，按跨步并集顺序从 1 编号——与 SSE sources 事件的 idx 同一口径。
 * 若让每个步骤自行编号，每步都从【来源1】开始，模型引用就和前端来源列表对不上。
 *
 * 超长时硬截断到 6000 字符并置 truncated，避免把上下文预算烧在历史复读上。
 */
public final class SynthesisPromptBuilder {

    /** 合成上下文上限（含目标、步骤产物、来源清单、当前问题） */
    public static final int MAX_CONTEXT_CHARS = 6_000;
    /** 单条来源正文上限：多步并集后仍要留出目标与当前问题的空间 */
    private static final int MAX_SOURCE_CHARS = 600;
    private static final String KB_SEARCH = "kb.search";
    private static final String EMPTY_OUTPUT = "（该步无产物）";
    private static final String NO_RECALL = "资料中未提及";
    private static final String SOURCE_SECTION = "召回来源（全局编号，与 sources 一致）：";

    private SynthesisPromptBuilder() {
    }

    /** 兼容只有步骤摘要的场景（无跨步并集来源）；PLAN 编排应传并集后的 hits */
    public static Context build(String question, String goal, List<StepOutcomeDTO> steps) {
        return build(question, goal, steps, List.of());
    }

    /**
     * @param hits 跨步并集后的来源列表（与 sources 事件同序），用于全局编号
     */
    public static Context build(String question, String goal, List<StepOutcomeDTO> steps,
                                List<SearchItemResponse> hits) {
        Map<Long, Integer> indexes = PlanSourceAggregator.globalIndexes(hits);
        StringBuilder body = new StringBuilder();
        body.append("本轮目标：").append(goal == null || goal.isBlank() ? question : goal).append('\n');
        body.append("步骤产物：\n");
        for (StepOutcomeDTO step : steps) {
            appendStep(body, step, indexes);
        }
        appendSources(body, hits);
        body.append("当前问题：").append(question);
        String text = body.toString();
        if (text.length() <= MAX_CONTEXT_CHARS) {
            return new Context(text, false);
        }
        return new Context(text.substring(0, MAX_CONTEXT_CHARS), true);
    }

    /** kb.search 有命中时只列全局编号（正文在来源清单里统一出现一次），其余步骤保留摘要 */
    private static void appendStep(StringBuilder body, StepOutcomeDTO step, Map<Long, Integer> indexes) {
        body.append('【').append(step.stepId()).append(' ').append(step.status().name()).append('】');
        if (KB_SEARCH.equals(step.tool()) && !step.sources().isEmpty()) {
            body.append("命中 ").append(step.sources().size()).append(" 条：")
                    .append(sourceRefs(step.sources(), indexes)).append('\n');
            return;
        }
        body.append(outputOf(step)).append('\n');
    }

    private static String sourceRefs(List<SearchItemResponse> sources, Map<Long, Integer> indexes) {
        StringBuilder refs = new StringBuilder();
        for (SearchItemResponse source : sources) {
            Integer idx = indexes.get(source.chunkId());
            if (idx == null) {
                continue;
            }
            if (!refs.isEmpty()) {
                refs.append('、');
            }
            refs.append("【来源").append(idx).append('】');
        }
        return refs.isEmpty() ? NO_RECALL : refs.toString();
    }

    private static void appendSources(StringBuilder body, List<SearchItemResponse> hits) {
        if (hits.isEmpty()) {
            return;
        }
        body.append(SOURCE_SECTION).append('\n');
        for (int i = 0; i < hits.size(); i++) {
            SearchItemResponse hit = hits.get(i);
            body.append("【来源").append(i + 1).append('】')
                    .append(hit.docTitle()).append(" / ")
                    .append(hit.sectionPath() == null ? "" : hit.sectionPath())
                    .append('\n').append(truncate(hit.content())).append('\n');
        }
    }

    private static String outputOf(StepOutcomeDTO step) {
        String summary = step.outputSummary();
        return summary == null || summary.isBlank() ? EMPTY_OUTPUT : summary;
    }

    private static String truncate(String content) {
        String text = content == null ? "" : content;
        return text.length() <= MAX_SOURCE_CHARS ? text : text.substring(0, MAX_SOURCE_CHARS) + "…";
    }

    /**
     * @param prompt    合成用的问题上下文
     * @param truncated 是否触发 6000 字符截断
     */
    public record Context(String prompt, boolean truncated) {
    }
}
