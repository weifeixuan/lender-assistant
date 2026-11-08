package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.dto.StepOutcomeDTO;
import com.lender.assistant.rag.model.dto.StepStatus;
import com.lender.assistant.rag.model.response.SearchItemResponse;
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
    @DisplayName("kb.search 降级步骤保留摘要供模型判读")
    void degradedKbStepKeepsSummaryInPrompt() {
        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build("问题", "目标",
                List.of(step("s1", "kb.search", StepStatus.DEGRADED, "资料中未提及")));

        assertTrue(context.prompt().contains("【s1 DEGRADED】资料中未提及"));
    }

    @Test
    @DisplayName("kb.search 来源按跨步并集全局编号，正文只在来源清单出现一次")
    void numbersKbSourcesGlobally() {
        SearchItemResponse a = source(1L, "A 文档", "A 正文");
        SearchItemResponse b = source(2L, "B 文档", "B 正文");
        SearchItemResponse c = source(3L, "C 文档", "C 正文");
        List<SearchItemResponse> union = List.of(a, b, c);

        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build("问题", "目标",
                List.of(stepWithSources("s1", List.of(a, b), 1),
                        stepWithSources("s2", List.of(b, c), 2)),
                union);

        assertTrue(context.prompt().contains("【s1 DONE】命中 2 条：【来源1】、【来源2】"),
                "第一步引用全局 1、2");
        assertTrue(context.prompt().contains("【s2 DONE】命中 2 条：【来源2】、【来源3】"),
                "第二步重复命中的 B 仍用全局 2，不重新从 1 开始");
        assertTrue(context.prompt().contains(union.get(2).content()), "来源清单包含正文");
        assertFalse(context.prompt().contains("【来源4】"), "编号不得超过并集大小");
        assertEquals(1, countOf(context.prompt(), "B 正文"), "重复命中的来源正文只出现一次");
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static StepOutcomeDTO stepWithSources(String stepId, List<SearchItemResponse> sources,
                                                   int order) {
        return new StepOutcomeDTO(stepId, order, "步骤", "kb.search", Map.of(), StepStatus.DONE, 10,
                sources.stream().map(SearchItemResponse::chunkId).toList(), sources, "摘要", null);
    }

    private static SearchItemResponse source(long chunkId, String title, String content) {
        return new SearchItemResponse(chunkId, "doc-1", title, "1.1", content, null, null, false, null);
    }

    private static StepOutcomeDTO step(String stepId, String tool, StepStatus status, String summary) {
        return new StepOutcomeDTO(stepId, 1, "步骤", tool, Map.of(), status, 10,
                List.of(), List.<SearchItemResponse>of(), summary, null);
    }
}
