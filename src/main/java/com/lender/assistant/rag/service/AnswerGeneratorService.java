package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.dto.AnswerCancellationDTO;
import com.lender.assistant.rag.model.dto.AnswerGenerationRequestDTO;

/**
 * 对话模型调用服务（六边形架构里的"输出端口"）。
 *
 * 为什么自己定义接口而不用 Spring AI 的 ChatModel：
 * - 我们只需要"给提示词、回流式 token"这一个能力，ChatModel 的接口面太大；
 * - 自建 2 方法的接口，替换成本更低。
 *
 * 入参是结构化召回结果而非拼好的整段 Prompt，避免把上下文序列化成文本再解析回来。
 *
 * 实现契约：onDelta 会被多次回调调用 token 增量，实现方保证顺序性；
 * generate 返回即代表生成结束（同步流式：回调在 generate 调用线程上依次发生）。
 *
 * 取消契约（2026-11 客户端断开取消）：SSE 断开时编排方 cancel 传入的
 * {@link AnswerCancellationDTO}，实现方必须尽快停止消费上游 token 并返回。
 * 取消后已回流的正文不回收，由编排方按 STOPPED 落库。
 */
public interface AnswerGeneratorService {

    /**
     * @param request 本次生成的完整入参（系统提示、历史、当前问题、召回、回调与取消句柄）
     */
    void generate(AnswerGenerationRequestDTO request);

    /** 实现标识，用于前端展示与日志（实际模型名） */
    String name();
}
