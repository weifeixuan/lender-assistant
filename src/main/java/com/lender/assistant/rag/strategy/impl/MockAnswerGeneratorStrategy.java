package com.lender.assistant.rag.strategy.impl;

import com.lender.assistant.rag.model.dto.AnswerCancellationDTO;
import com.lender.assistant.rag.model.dto.AnswerGenerationRequestDTO;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import com.lender.assistant.tools.ToolIntentRouter.ToolIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Mock 对话模型：不调用任何大模型 API，把召回上下文"复述"成回答。
 *
 * 用途与 HashEmbeddingStrategy 完全同构——key 没到位时让
 * "检索 → 拼 Prompt → 生成 → SSE 流式"整条管道可开发、可调试、可验收管道本身。
 *
 * 工具路径（阶段4）：问题命中工具意图时，真实调用 LenderQueryTools 并把
 * 工具结果直接编入回答，回答显式带【工具调用】标记——决策由 ToolIntentRouter
 * 规则模拟（真实决策在真实模型版的 Spring AI .tools()）。
 *
 * 诚实声明（对用户与面试官都一样）：它产生的是"检索结果摘要"，不是语义回答，
 * 不能用于评估回答质量——阶段 2 的 5 个验收用例要在 key 到位、切 openai 后复测。
 *
 * 流式模拟：按固定字数切 delta、间隔 30ms，让前端真的走增量渲染路径，
 * 而不是"一次性大块"恰好把流式 bug 藏过去。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "mock", matchIfMissing = true)
public class MockAnswerGeneratorStrategy implements AnswerGeneratorStrategy {

    private static final Logger log = LoggerFactory.getLogger(MockAnswerGeneratorStrategy.class);

    /** 流式模拟：每片 delta 的字符数 */
    private static final int STREAM_DELTA_CHARS = 12;
    /** 流式模拟：片间间隔（ms），让前端真的走增量渲染路径而非"一次性大块" */
    private static final long STREAM_DELTA_INTERVAL_MILLIS = 30;
    /** Mock 摘要每条来源截取的前缀字符数——长段落只留开头，避免回答臃肿 */
    private static final int SUMMARY_PREVIEW_CHARS = 60;

    private final com.lender.assistant.tools.LenderQueryTools lenderTools;
    private final com.lender.assistant.tools.ToolIntentRouter toolRouter;

    public MockAnswerGeneratorStrategy(com.lender.assistant.tools.LenderQueryTools lenderTools,
                                       com.lender.assistant.tools.ToolIntentRouter toolRouter) {
        this.lenderTools = lenderTools;
        this.toolRouter = toolRouter;
    }

    @Override
    public void generate(AnswerGenerationRequestDTO request) {
        // Mock 不模拟多轮语义：签名与真实实现保持一致，历史直接忽略
        streamAnswer(buildAnswer(request.question(), request.contextHits()), request.onDelta(),
                request.cancellation());
    }

    private String buildAnswer(String question, List<SearchItemResponse> contextHits) {
        // 工具意图 → 模拟"模型决定调用" → 真实查库 → 结果直接回答
        // knownDocIds 惰性求值：普通问题零额外查询
        Optional<ToolIntent> intent = toolRouter.route(question, lenderTools::knownDocIds);
        if (intent.isPresent()) {
            ToolIntent toolIntent = intent.get();
            String toolResult = "listIngestedDocs".equals(toolIntent.toolName())
                    ? lenderTools.listIngestedDocs()
                    : lenderTools.getDocChunkStats(toolIntent.argument());
            return "【工具调用】" + toolIntent.toolName() + "（Mock 模式：规则模拟模型决策，"
                    + "真实模型版由模型按工具 description 自主决定）\n"
                    + "调用理由: " + toolIntent.reason() + "\n\n"
                    + "工具返回：\n" + toolResult + "\n\n"
                    + "（以上为工具直查数据库的真实结果；语义总结需配置 LLM_API_KEY）";
        }

        // Mock 直接基于召回结果拼一个摘要式回答（每条来源截取前 60 字）。
        StringBuilder answer = new StringBuilder();
        answer.append("【Mock 模式回答】当前未配置对话模型 API key，以下是依据召回内容生成的摘要（非语义回答）：\n\n");
        for (int i = 0; i < contextHits.size(); i++) {
            SearchItemResponse hit = contextHits.get(i);
            answer.append("【来源").append(i + 1).append("】")
                  .append(hit.docTitle()).append(" / ")
                  .append(hit.sectionPath() == null ? "（无小节）" : hit.sectionPath())
                  .append("\n");
            String content = hit.content() == null ? "" : hit.content().trim();
            answer.append(content, 0, Math.min(SUMMARY_PREVIEW_CHARS, content.length()))
                  .append(content.length() > SUMMARY_PREVIEW_CHARS ? "……\n" : "\n");
        }
        answer.append("\n（配置 LLM_API_KEY 并设置 rag.chat.provider=openai 后，这里将替换为真实模型回答）");
        return answer.toString();
    }

    private void streamAnswer(String full, Consumer<String> onDelta, AnswerCancellationDTO cancellation) {
        for (int i = 0; i < full.length(); i += STREAM_DELTA_CHARS) {
            // 客户端断开后立即停止：不再切片、不再回调（模拟"停止消费上游 token"）
            if (cancellation.isCancelled()) {
                log.debug("生成已取消，剩余片段不再推送");
                return;
            }
            onDelta.accept(full.substring(i, Math.min(i + STREAM_DELTA_CHARS, full.length())));
            try {
                Thread.sleep(STREAM_DELTA_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                // 中断（应用关停 / SSE 客户端取消）：恢复中断标志让上层感知，随即停止推送。
                // 属预期终止路径，记 DEBUG 即可
                log.debug("流式推送被中断，提前结束（客户端可能已取消）");
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public String name() {
        return "Mock";
    }
}
