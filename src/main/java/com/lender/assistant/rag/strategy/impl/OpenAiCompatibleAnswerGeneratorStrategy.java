package com.lender.assistant.rag.strategy.impl;

import com.lender.assistant.rag.model.dto.AnswerGenerationRequestDTO;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import com.lender.assistant.rag.model.dto.ChatTurnDTO;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import com.lender.assistant.aitools.LenderQueryTools;
import com.lender.assistant.resilience.LlmCallLogger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private final LlmCallLogger llmCallLogger;
    private final String model;

    public OpenAiCompatibleAnswerGeneratorStrategy(ChatClient.Builder builder,
                                                   LenderQueryTools lenderTools,
                                                   LlmCallLogger llmCallLogger,
                                                   @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = builder.build();
        this.lenderTools = lenderTools;
        this.llmCallLogger = llmCallLogger;
        this.model = model;
    }

    @Override
    public void generate(AnswerGenerationRequestDTO request) {
        List<Message> messages = new ArrayList<>();
        for (ChatTurnDTO turn : request.history()) {
            messages.add("ASSISTANT".equals(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
        }
        // 当前问题（含召回资料）永远最后追加，历史只作为前序对话
        messages.add(new UserMessage(buildUserPrompt(request.question(), request.contextHits())));

        // 取消信号：SSE 断开时 takeUntilOther 立即 dispose 上游订阅，blockLast 立刻返回，
        // 不再继续消费 token（Flux 可能正卡在等下一个 token 的网络等待里，光靠标记位停不下来）
        Sinks.One<Boolean> cancelled = Sinks.one();
        request.cancellation().onCancel(() -> cancelled.tryEmitValue(Boolean.TRUE));

        // 用 chatResponse 而不是 content：需要从响应元数据里取 token 计量，交给外层 LlmCallLogger 落库
        Flux<ChatResponse> flux = chatClient.prompt()
                .system(request.systemPrompt())
                .messages(messages)
                // Function Calling：把业务查询工具挂给模型，由模型按工具 description
                // 自主决定是否调用（真 Function Calling）。Mock 版的规则模拟见 ToolIntentRouter。
                .tools(lenderTools)
                .stream()
                .chatResponse()
                .timeout(STREAM_TIMEOUT);
        // 同步流式：token 依次回调，流结束（或取消）才返回（QaService 依赖这个契约收尾 SSE）
        flux.takeUntilOther(cancelled.asMono())
                .doOnNext(response -> {
                    String delta = textOf(response);
                    if (!delta.isEmpty()) {
                        request.onDelta().accept(delta);
                    }
                    // 流式通常只有末块带 Usage；每次回填取最新值，拿不到则留 NULL
                    llmCallLogger.captureUsage(response);
                })
                .blockLast();
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 把结构化召回结果按【来源N】拼成用户提示词，问题放在末尾 */
    private String buildUserPrompt(String question, List<SearchItemResponse> contextHits) {
        StringBuilder prompt = new StringBuilder("【召回资料】\n");
        for (int i = 0; i < contextHits.size(); i++) {
            SearchItemResponse hit = contextHits.get(i);
            prompt.append("【来源").append(i + 1).append("】")
                  .append(hit.docTitle()).append(" / ")
                  .append(hit.sectionPath() == null ? "（无小节）" : hit.sectionPath())
                  .append("\n").append(hit.content()).append("\n\n");
        }
        prompt.append("【问题】").append(question);
        return prompt.toString();
    }

    @Override
    public String name() {
        // 返回实际模型名而非固定厂商名：切换模型后日志/前端能区分是哪一路回答
        return model;
    }
}
