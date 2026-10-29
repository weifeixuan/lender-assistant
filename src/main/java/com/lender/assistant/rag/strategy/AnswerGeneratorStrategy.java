package com.lender.assistant.rag.strategy;

import java.util.function.Consumer;

/**
 * 对话模型的策略接口（六边形架构里的"输出端口"）。
 *
 * 为什么自己定义接口而不用 Spring AI 的 ChatModel：
 * - 我们只需要"给提示词、回流式 token"这一个能力，ChatModel 的接口面太大；
 * - Mock 实现不需要绕过 Spring AI 的抽象去伪装一个 ChatModel——
 *   实现自己这个 3 方法的接口，20 行就够，替换成本更低。
 *
 * provider 二选一（mock / openai）由 @ConditionalOnProperty 在构建期决定，
 * 容器里始终只有一个实现，直接按接口注入即可，不需要工厂。
 *
 * 实现契约：onDelta 会被多个线程/多次回调调用 token 增量，实现方保证顺序性；
 * generate 返回即代表生成结束（同步流式：回调在 generate 调用线程上依次发生）。
 */
public interface AnswerGeneratorStrategy {

    /**
     * @param systemPrompt 系统提示词（角色与三条铁律）
     * @param userPrompt   用户提示词（召回上下文 + 问题）
     * @param onDelta      每收到一段 token 文本回调一次
     */
    void generate(String systemPrompt, String userPrompt, Consumer<String> onDelta);

    /** 实现标识，用于前端展示与日志（如 "Mock" / 实际模型名） */
    String name();
}
