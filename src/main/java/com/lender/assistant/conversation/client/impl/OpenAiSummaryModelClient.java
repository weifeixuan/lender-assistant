package com.lender.assistant.conversation.client.impl;

import com.lender.assistant.common.constant.LlmCallConstants;
import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.conversation.client.SummaryModelClient;
import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 滚动摘要模型：OpenAI 兼容非流式调用（PRD §2.5）。
 *
 * 与 OpenAiRoutingModelClient 同构：经 ResilientLlmExecutor 统一留痕（scene=summary）/
 * 熔断/重试（非流式 allowRetry=true），Reactor timeout 复用共享 NON_STREAM_TIMEOUT。
 * 摘要失败静默降级由调用方处理：保留旧摘要（边界不推进）、记 WARN、不挡主链路。
 */
@Component
public class OpenAiSummaryModelClient implements SummaryModelClient {

    private final ChatClient chatClient;
    private final ResilientLlmExecutor executor;
    private final LlmCallLogger llmCallLogger;
    private final String model;

    public OpenAiSummaryModelClient(ChatClient.Builder builder, ResilientLlmExecutor executor,
                                    LlmCallLogger llmCallLogger,
                                    @Value("${spring.ai.openai.chat.options.model:openai}") String model) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.llmCallLogger = llmCallLogger;
        this.model = model;
    }

    /**
     * 经 ResilientLlmExecutor 统一留痕/熔断（PRD §2.5）：scene=SUMMARY，非流式允许重试。
     * 包裹的是「原始模型调用」而不是整个 complete()，模型异常记为 FAIL 并计入熔断；
     * 失败原样上抛，静默降级语义归调用方。
     */
    @Override
    public String complete(String prompt) {
        return executor.execute(LlmScene.SUMMARY, model,
                () -> Mono.fromCallable(() -> {
                            ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
                            // 非流式响应的 Usage 可直接取；由外层 record() 统一落库
                            llmCallLogger.captureUsage(response);
                            return response.getResult().getOutput().getText();
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(LlmCallConstants.NON_STREAM_TIMEOUT)
                        .block(),
                true);
    }
}
