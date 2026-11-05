package com.lender.assistant.rag.plan;

import com.lender.assistant.rag.plan.model.StepOutcome;
import com.lender.assistant.rag.plan.model.StepStatus;

import java.util.List;

/**
 * PLAN 轮合成上下文：只拼「目标 + 步骤产物 + 当前问题」，不重复完整对话历史。
 * 超长时硬截断到 6000 字符并置 truncated，避免把上下文预算烧在历史复读上。
 */
public final class SynthesisPromptBuilder {

    /** 合成上下文上限（含目标、步骤产物、当前问题） */
    public static final int MAX_CONTEXT_CHARS = 6_000;
    private static final String KB_SEARCH = "kb.search";
    private static final String EMPTY_OUTPUT = "（该步无产物）";

    private SynthesisPromptBuilder() {
    }

    public static Context build(String question, String goal, List<StepOutcome> steps) {
        StringBuilder body = new StringBuilder();
        body.append("本轮目标：").append(goal == null || goal.isBlank() ? question : goal).append('\n');
        body.append("步骤产物：\n");
        boolean businessOutput = false;
        for (StepOutcome step : steps) {
            body.append('【').append(step.stepId()).append(' ').append(step.status().name()).append('】');
            body.append(outputOf(step)).append('\n');
            businessOutput |= hasBusinessOutput(step);
        }
        body.append("当前问题：").append(question);
        String text = body.toString();
        if (text.length() <= MAX_CONTEXT_CHARS) {
            return new Context(text, false, businessOutput);
        }
        return new Context(text.substring(0, MAX_CONTEXT_CHARS), true, businessOutput);
    }

    private static String outputOf(StepOutcome step) {
        String summary = step.outputSummary();
        return summary == null || summary.isBlank() ? EMPTY_OUTPUT : summary;
    }

    /** 业务工具产出才算"有资料可答"；kb.search 自身命中与否由来源并集判断 */
    private static boolean hasBusinessOutput(StepOutcome step) {
        return !KB_SEARCH.equals(step.tool()) && step.status() == StepStatus.DONE;
    }

    /**
     * @param prompt          合成用的问题上下文
     * @param truncated       是否触发 6000 字符截断
     * @param businessOutput  是否有 mock 业务工具的正常产物
     */
    public record Context(String prompt, boolean truncated, boolean businessOutput) {
    }
}
