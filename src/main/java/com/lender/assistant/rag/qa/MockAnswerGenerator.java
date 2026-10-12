package com.lender.assistant.rag.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Mock 对话模型：不调用任何大模型 API，把召回上下文"复述"成回答。
 *
 * 用途与 HashEmbeddingClient 完全同构——key 没到位时让
 * "检索 → 拼 Prompt → 生成 → SSE 流式"整条管道可开发、可调试、可验收管道本身。
 *
 * 诚实声明（对用户与面试官都一样）：它产生的是"检索结果摘要"，不是语义回答，
 * 不能用于评估回答质量——阶段 2 的 5 个验收用例要在 key 到位、切 deepseek 后复测。
 *
 * 流式模拟：按固定字数切 delta、间隔 30ms，让前端真的走增量渲染路径，
 * 而不是"一次性大块"恰好把流式 bug 藏过去。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "mock", matchIfMissing = true)
public class MockAnswerGenerator implements AnswerGenerator {

    @Override
    public void generate(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        // userPrompt 的结构由 QaService 约定：铁律提示 + 【来源 N】上下文 + 问题。
        // Mock 直接基于上下文段落拼一个摘要式回答（截取每条来源的前 60 字）。
        StringBuilder answer = new StringBuilder();
        answer.append("【Mock 模式回答】当前未配置对话模型 API key，以下是依据召回内容生成的摘要（非语义回答）：\n\n");
        String[] lines = userPrompt.split("\n");
        boolean inContext = false;
        for (String line : lines) {
            if (line.startsWith("【来源")) {
                inContext = true;
                answer.append(line).append("\n");
            } else if (inContext) {
                if (line.startsWith("【问题】")) {
                    break;
                }
                if (!line.isBlank()) {
                    String text = line.trim();
                    answer.append(text, 0, Math.min(60, text.length()))
                          .append(text.length() > 60 ? "……\n" : "\n");
                }
            }
        }
        answer.append("\n（配置 DeepSeek key 并设置 rag.chat.provider=deepseek 后，这里将替换为真实模型回答）");

        String full = answer.toString();
        for (int i = 0; i < full.length(); i += 12) {
            onDelta.accept(full.substring(i, Math.min(i + 12, full.length())));
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
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
