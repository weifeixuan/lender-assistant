package com.lender.assistant.conversation.client;

/**
 * 滚动摘要生成的模型调用入口（PRD §2.5）：与路由/计划侧同构的一次非流式补全。
 * 抽成函数式接口是为了单测能直接传 lambda，不必起 Spring 上下文。
 * 调用失败原样上抛，静默降级（保留旧摘要、记 WARN）由调用方处理。
 */
@FunctionalInterface
public interface SummaryModelClient {

    /** 传入完整 prompt（{@code QaPrompts.summaryPrompt} 渲染），返回模型完整摘要正文；硬截断归调用方 */
    String complete(String prompt);
}
