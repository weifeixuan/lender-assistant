package com.lender.assistant.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 契约：四段 prompt 是模型输出格式与解析逻辑的共同约定，
 * 任何一次改写都必须保持分类枚举、JSON 输出、无编造、引用编号这几条不变量。
 */
class QaPromptsContractTest {

    @Test
    @DisplayName("路由 prompt：分类枚举与 JSON 输出契约不变，占位符全部替换")
    void routePromptKeepsContract() {
        String prompt = QaPrompts.routePrompt("上一轮问题", "当前问题", "KNOWLEDGE", "doc-1");

        assertTrue(prompt.contains("IRRELEVANT"));
        assertTrue(prompt.contains("KNOWLEDGE"));
        assertTrue(prompt.contains("PLAN"));
        assertTrue(prompt.contains("只输出 JSON"));
        assertTrue(prompt.contains("当前问题"));
        assertFalse(prompt.contains("%s"), "prompt 里不能残留未替换的占位符");
    }

    @Test
    @DisplayName("计划 prompt：步骤数、kb.search 强制项、可用工具都不丢")
    void planPromptKeepsContract() {
        String prompt = QaPrompts.planPrompt(2, 5, null, "当前问题", null, "customer.profile, kb.search");

        assertTrue(prompt.contains("2–5"), "步骤数上下限必须落在 prompt 里");
        assertTrue(prompt.contains("至少包含一个 kb.search"));
        assertTrue(prompt.contains("customer.profile"));
        assertTrue(prompt.contains("只输出 JSON"));
        assertFalse(prompt.contains("%d"), "prompt 里不能残留未替换的占位符");
    }

    @Test
    @DisplayName("回答 prompt：无编造与引用编号两条铁律保留")
    void answerPromptsKeepInvariants() {
        assertTrue(QaPrompts.KNOWLEDGE_SYSTEM_PROMPT.contains("资料中未提及"));
        assertTrue(QaPrompts.KNOWLEDGE_SYSTEM_PROMPT.contains("【来源1】"));
        assertTrue(QaPrompts.SYNTHESIS_SYSTEM_PROMPT.contains("资料中未提及"));
        assertTrue(QaPrompts.SYNTHESIS_SYSTEM_PROMPT.contains("编号与 sources 一致"));
    }
}
