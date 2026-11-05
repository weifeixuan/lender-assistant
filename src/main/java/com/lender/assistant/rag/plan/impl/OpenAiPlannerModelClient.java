package com.lender.assistant.rag.plan.impl;

import com.lender.assistant.rag.plan.PlannerModelClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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

    public OpenAiPlannerModelClient(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String complete(String prompt) {
        return Mono.fromCallable(() -> chatClient.prompt().user(prompt).call().content())
                .timeout(NON_STREAM_TIMEOUT)
                .block();
    }
}
