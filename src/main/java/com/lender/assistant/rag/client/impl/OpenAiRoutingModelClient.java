package com.lender.assistant.rag.client.impl;

import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.rag.client.RoutingModelClient;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final ResilientLlmExecutor executor;
    private final String model;

    public OpenAiRoutingModelClient(ChatClient.Builder builder, ResilientLlmExecutor executor,
                                    @Value("${spring.ai.openai.chat.options.model:openai}") String model) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.model = model;
    }

    /**
     * 经 ResilientLlmExecutor 统一留痕/熔断（PRD §2.4）：scene=ROUTE，非流式允许重试。
     * 包裹的是「原始模型调用」而不是整个 route()，这样模型异常会被记为 FAIL 并计入熔断，
     * 上层 QuestionRoutingServiceImpl 仍按 fail-open 规则降级 KNOWLEDGE。
     */
    @Override
    public String complete(String prompt) {
        return executor.execute(LlmScene.ROUTE, model,
                () -> Mono.fromCallable(() -> chatClient.prompt().user(prompt).call().content())
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(NON_STREAM_TIMEOUT)
                        .block(),
                true);
    }
}
