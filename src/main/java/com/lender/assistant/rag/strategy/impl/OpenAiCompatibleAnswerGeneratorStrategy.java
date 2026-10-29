package com.lender.assistant.rag.strategy.impl;

import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import com.lender.assistant.tools.LenderQueryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 真实模型问答：走 OpenAI 兼容协议（Spring AI ChatClient 流式接口）。
 *
 * 为什么是"兼容"而不是某个厂商：DeepSeek / Kimi / 通义 / GLM 等都提供 OpenAI 兼容端点，
 * 换模型只改 application.yml 里的 base-url + model + key，本类代码不动。
 *
 * 要点：
 * - ChatClient.stream() 返回 Reactor Flux<String>，我们用 doOnNext 逐 token 回调、
 *   blockLast() 在调用线程上等流结束——Flux 只在服务内部存活，不污染 Servlet 栈；
 * - timeout(60s)：模型偶发长时间不吐 token 时兜底，防 SSE 连接被无限挂着
 *   （阶段 4 的 Resilience4j 超时/熔断/留痕在它的外层）；
 * - api-key 由 starter 从 LLM_API_KEY 环境变量读取，不进代码库。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "openai")
public class OpenAiCompatibleAnswerGeneratorStrategy implements AnswerGeneratorStrategy {

    /** 流式兜底超时：模型偶发长时间不吐 token 时防 SSE 连接被无限挂着 */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(60);

    private final ChatClient chatClient;
    private final LenderQueryTools lenderTools;
    private final String model;

    public OpenAiCompatibleAnswerGeneratorStrategy(ChatClient.Builder builder,
                                                   LenderQueryTools lenderTools,
                                                   @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = builder.build();
        this.lenderTools = lenderTools;
        this.model = model;
    }

    @Override
    public void generate(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Flux<String> flux = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                // Function Calling：把业务查询工具挂给模型，由模型按工具 description
                // 自主决定是否调用（真 Function Calling）。Mock 版的规则模拟见 ToolIntentRouter。
                .tools(lenderTools)
                .stream()
                .content()
                .timeout(STREAM_TIMEOUT);
        // 同步流式：token 依次回调，流结束才返回（QaService 依赖这个契约收尾 SSE）
        flux.doOnNext(onDelta::accept).blockLast();
    }

    @Override
    public String name() {
        // 返回实际模型名而非固定厂商名：切换模型后日志/前端能区分是哪一路回答
        return model;
    }
}
