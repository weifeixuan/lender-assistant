package com.lender.assistant.rag.plan;

import com.lender.assistant.rag.plan.model.StepOutcome;
import com.lender.assistant.rag.plan.model.StepStatus;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynthesisPromptBuilderTest {

    @Test
    @DisplayName("上下文含目标与每步产物，不拼接历史")
    void buildsGoalAndStepOutputs() {
        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build("单笔限额", "查限额",
                List.of(step("s1", "kb.search", StepStatus.DONE, "资料写 500 万"),
                        step("s2", "customer.profile", StepStatus.DONE, "可用额度 800 万")));

        assertTrue(context.prompt().contains("本轮目标：查限额"));
        assertTrue(context.prompt().contains("【s1 DONE】资料写 500 万"));
        assertTrue(context.prompt().contains("【s2 DONE】可用额度 800 万"));
        assertTrue(context.prompt().endsWith("当前问题：单笔限额"));
        assertFalse(context.truncated());
        assertTrue(context.businessOutput());
    }

    @Test
    @DisplayName("目标为空时回退到当前问题")
    void fallsBackToQuestionWhenGoalBlank() {
        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build("限额", "",
                List.of(step("s1", "kb.search", StepStatus.DONE, "内容")));

        assertTrue(context.prompt().contains("本轮目标：限额"));
    }

    @Test
    @DisplayName("超过 6000 字符硬截断并置 truncated")
    void truncatesOverLimit() {
        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build("问题", "目标",
                List.of(step("s1", "kb.search", StepStatus.DONE, "长".repeat(7_000))));

        assertEquals(SynthesisPromptBuilder.MAX_CONTEXT_CHARS, context.prompt().length());
        assertTrue(context.truncated());
    }

    @Test
    @DisplayName("kb.search 降级不算业务产物")
    void kbDegradedIsNotBusinessOutput() {
        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build("问题", "目标",
                List.of(step("s1", "kb.search", StepStatus.DEGRADED, "资料中未提及")));

        assertFalse(context.businessOutput());
        assertTrue(context.prompt().contains("【s1 DEGRADED】资料中未提及"));
    }

    private static StepOutcome step(String stepId, String tool, StepStatus status, String summary) {
        return new StepOutcome(stepId, 1, "步骤", tool, Map.of(), status, 10,
                List.of(), List.<SearchItemResponse>of(), summary, null);
    }
}
