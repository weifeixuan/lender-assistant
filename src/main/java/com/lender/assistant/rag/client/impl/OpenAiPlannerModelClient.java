package com.lender.assistant.rag.client.impl;

import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.rag.client.PlannerModelClient;
import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 真实计划模型：OpenAI 兼容非流式调用。
 * 单次超时与路由侧一致（10s），与 {@code rag.plan.total-budget-ms} 由执行器统一核算。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "openai")
public class OpenAiPlannerModelClient implements PlannerModelClient {

    private static final Duration NON_STREAM_TIMEOUT = Duration.ofSeconds(10);

    private final ChatClient chatClient;
    private final ResilientLlmExecutor executor;
    private final LlmCallLogger llmCallLogger;
    private final String model;

    public OpenAiPlannerModelClient(ChatClient.Builder builder, ResilientLlmExecutor executor,
                                    LlmCallLogger llmCallLogger,
                                    @Value("${spring.ai.openai.chat.options.model:openai}") String model) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.llmCallLogger = llmCallLogger;
        this.model = model;
    }

    /**
     * 经 ResilientLlmExecutor 统一留痕/熔断（PRD §2.4）：scene=PLAN。
     * 重试交给 PlanAndExecuteService 的两轮尝试（票 06 约束「模型调用最多 2 次」），
     * 这里 allowRetry=false，避免与外层重试叠乘。
     */
    @Override
    public String complete(String prompt) {
        return executor.execute(LlmScene.PLAN, model,
                () -> Mono.fromCallable(() -> {
                            ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
                            // 非流式响应的 Usage 可直接取；由外层 record() 统一落库
                            llmCallLogger.captureUsage(response);
                            return response.getResult().getOutput().getText();
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(NON_STREAM_TIMEOUT)
                        .block(),
                false);
    }
}
