package com.lender.assistant.rag.strategy;

import com.lender.assistant.rag.model.ChatTurn;
import com.lender.assistant.rag.vo.response.SearchItemResponse;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一次生成的完整入参：系统提示、有序历史、当前问题、召回结果、增量回调与取消句柄。
 *
 * 收成一个 record，新增字段时只改这里，不必让接口与两个实现、全部测试逐个改签名。
 * 放在 strategy 包内而不是 model/vo，是为了让"端口的入参"与端口本身同包，避免包级依赖成环。
 */
public record AnswerGenerationRequest(
        String systemPrompt,
        List<ChatTurn> history,
        String question,
        List<SearchItemResponse> contextHits,
        Consumer<String> onDelta,
        AnswerCancellation cancellation) {
}
