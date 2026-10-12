package com.lender.assistant.rag.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * DeepSeek 真实实现：走 Spring AI ChatClient 流式接口。
 *
 * 要点：
 * - ChatClient.stream() 返回 Reactor Flux<String>，我们用 doOnNext 逐 token 回调、
 *   blockLast() 在调用线程上等流结束——Flux 只在服务内部存活，不污染 Servlet 栈；
 * - timeout(60s)：模型偶发长时间不吐 token 时兜底，防 SSE 连接被无限挂着
 *   （阶段 4 会升级成 Resilience4j 的超时/熔断/留痕，这里是它的前身）；
 * - api-key 仍由 starter 从 DEEPSEEK_API_KEY 环境变量读取，不进代码库。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "deepseek")
public class DeepSeekAnswerGenerator implements AnswerGenerator {

    private final org.springframework.ai.chat.client.ChatClient chatClient;

    public DeepSeekAnswerGenerator(org.springframework.ai.chat.client.ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public void generate(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Flux<String> flux = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .timeout(Duration.ofSeconds(60));
        // 同步流式：token 依次回调，流结束才返回（QaService 依赖这个契约收尾 SSE）
        flux.doOnNext(onDelta::accept).blockLast();
    }

    @Override
    public String name() {
        return "DeepSeek";
    }
}
