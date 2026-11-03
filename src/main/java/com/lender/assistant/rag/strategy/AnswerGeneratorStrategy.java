package com.lender.assistant.rag.strategy;

import com.lender.assistant.rag.vo.ChatTurn;
import com.lender.assistant.rag.vo.response.SearchItemResponse;

import java.util.List;
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
 * 入参是结构化召回结果而非拼好的整段 Prompt：每个实现自己决定怎么用
 * （真实模型拼 Prompt，Mock 直接摘要），避免把上下文序列化成文本再解析回来。
 *
 * 实现契约：onDelta 会被多个线程/多次回调调用 token 增量，实现方保证顺序性；
 * generate 返回即代表生成结束（同步流式：回调在 generate 调用线程上依次发生）。
 *
 * 取消契约（2026-11 客户端断开取消）：SSE 断开时编排方 cancel 传入的
 * {@link AnswerCancellation}，实现方必须尽快停止消费上游 token 并返回——
 * Mock 每片之间轮询，真实模型 dispose Reactor 订阅。取消后已回流的正文不回收，
 * 由编排方按 STOPPED 落库。
 */
public interface AnswerGeneratorStrategy {

    /**
     * @param systemPrompt 系统提示词（角色与三条铁律）
     * @param history      有序历史（USER/ASSISTANT 正文），已按完整轮次过滤与裁剪
     * @param question     当前问题
     * @param contextHits  本次召回结果（按召回顺序对应【来源1】、【来源2】…）
     * @param onDelta      每收到一段 token 文本回调一次
     * @param cancellation 取消句柄；实现方据此停止消费上游
     */
    void generate(String systemPrompt, List<ChatTurn> history, String question,
                  List<SearchItemResponse> contextHits, Consumer<String> onDelta,
                  AnswerCancellation cancellation);

    /** 实现标识，用于前端展示与日志（如 "Mock" / 实际模型名） */
    String name();
}
