package com.lender.assistant.rag.routing.impl;

import com.lender.assistant.rag.routing.RoutingModelClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 真实路由模型：OpenAI 兼容非流式调用。
 *
 * 超时用 Reactor 的 timeout 而不是 Resilience4j TimeLimiter：
 * 后者需要额外线程池，对单次非流式调用是过度设计；熔断与重试仍在外层执行器。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "openai")
public class OpenAiRoutingModelClient implements RoutingModelClient {

    /** 路由单次调用上限：分类输入 200–400 token、输出 ≤30 token，10s 足够宽 */
    private static final Duration NON_STREAM_TIMEOUT = Duration.ofSeconds(10);

    private final ChatClient chatClient;

    public OpenAiRoutingModelClient(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String complete(String prompt) {
        return Mono.fromCallable(() -> chatClient.prompt().user(prompt).call().content())
                .timeout(NON_STREAM_TIMEOUT)
                .block();
    }
}
