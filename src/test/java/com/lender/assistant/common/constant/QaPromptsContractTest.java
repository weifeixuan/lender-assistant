package com.lender.assistant.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("摘要 prompt：只依据输入、不编造、不加小标题、一段连贯中文梗概四条铁律保留")
    void summaryPromptKeepsContract() {
        String prompt = QaPrompts.summaryPrompt("已有摘要：用户关注华信租赁小额产品线。",
                "【第1轮】用户：小额产品的额度上限是多少？\n助手：根据【来源1】……\n"
                        + "【第2轮】用户：需要的材料有哪些？\n助手：资料中未提及。");

        assertTrue(prompt.contains("只依据"), "铁律：只能依据输入的轮次原文与已有摘要");
        assertTrue(prompt.contains("不得编造"), "铁律：不编造输入之外的事实");
        assertTrue(prompt.contains("不要使用小标题"), "铁律：不加小标题");
        assertTrue(prompt.contains("一段连贯"), "铁律：输出一段连贯的中文梗概");
        assertTrue(prompt.contains("已有摘要：用户关注华信租赁小额产品线。"), "已有摘要必须逐字进入 prompt");
        assertTrue(prompt.contains("【第1轮】用户：小额产品的额度上限是多少？"), "被裁轮次原文必须逐字进入 prompt");
        assertFalse(prompt.contains("%s"), "prompt 里不能残留未替换的占位符");
    }

    @Test
    @DisplayName("摘要注入块：固定标签、仅作背景参考声明与摘要正文逐字拼入")
    void summaryBlockKeepsContract() {
        // 逐字契约：注入块文案与 PRD §3 渲染样例一致，任何改动都必须同步评审
        assertEquals("【更早对话摘要】以下是本会话回放窗口之外更早轮次的梗概，仅作背景参考：\n"
                        + "用户早期确认关注华信租赁小额产品线。",
                QaPrompts.summaryBlock("用户早期确认关注华信租赁小额产品线。"));
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
