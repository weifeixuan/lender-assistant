package com.lender.assistant.rag.qa;

import com.lender.assistant.rag.SearchService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答编排（阶段 2 核心）：检索 → 拼 Prompt → 模型生成 → SSE 流式推给前端。
 *
 * Prompt 三铁律（RAG 的价值所在，面试必讲）：
 * 1. 只准依据召回内容回答——把模型的"自由发挥面"压缩到召回质量上，这是治幻觉的根；
 * 2. 召回内容不足时明确说"资料中未提及"，禁止编造——宁可答不出来，不可答错；
 * 3. 回答引用【来源 N】，为阶段 3 的引用溯源打底。
 *
 * SSE 事件协议（前端按 event 名分发）：
 * - sources: JSON 数组，本次召回的来源列表（先发，前端可先渲染"正在引用 N 份资料"）
 * - delta:   纯文本，模型 token 增量，前端增量拼接渲染
 * - done:    JSON，{provider, elapsedMillis}，收尾信号
 * - error:   纯文本，服务端异常信息（之后 completeWithError 断流）
 */
@Service
public class QaService {

    private final SearchService searchService;
    private final AnswerGenerator generator;

    public QaService(SearchService searchService, AnswerGenerator generator) {
        this.searchService = searchService;
        this.generator = generator;
    }

    public SseEmitter answer(String question, int topK) {
        // 120s 超时：覆盖"检索 + 模型生成全流程"，DeepSeek 正常问答远快于这个值
        SseEmitter emitter = new SseEmitter(120_000L);
        long t0 = System.currentTimeMillis();

        // 检索与 Prompt 组装在请求线程完成（快，且有 DB 异常要立刻暴露）；
        // 模型流式生成也在同一线程（AnswerGenerator 契约是同步回调），
        // SseEmitter 的 send 线程安全，无需额外切换线程池。
        try {
            List<Map<String, Object>> hits = searchService.hybridSearch(question, topK);
            if (hits.isEmpty()) {
                send(emitter, "delta", "资料库中没有检索到相关内容，请先在上方上传并入库文档。");
                emitter.complete();
                return emitter;
            }

            // 先推来源列表，前端立刻能展示"引用了哪些资料"
            List<Map<String, Object>> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                Map<String, Object> h = hits.get(i);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("idx", i + 1);
                s.put("docTitle", h.get("docTitle"));
                s.put("sectionPath", h.get("sectionPath"));
                sources.add(s);
            }
            send(emitter, "sources", sources);

            // AnswerGenerator 契约：generate 返回时生成已结束，token 在调用线程上依次回调
            generator.generate(systemPrompt(), userPrompt(question, hits),
                    token -> send(emitter, "delta", token));

            Map<String, Object> done = Map.of(
                    "provider", generator.name(),
                    "elapsedMillis", System.currentTimeMillis() - t0,
                    "sourceCount", sources.size());
            send(emitter, "done", done);
            emitter.complete();
        } catch (Exception e) {
            // 模型超时/网络异常/DB 异常统一走 error 事件——前端能拿到可读信息而非静默断流
            send(emitter, "error", "问答失败：" + e.getMessage());
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private String systemPrompt() {
        return """
                你是融资租赁资金方对接场景的资料问答助手。严格遵守三条铁律：
                1. 只依据下方提供的召回资料回答，不得使用资料之外的任何知识；
                2. 召回资料不足以回答问题时，必须明确回答"资料中未提及"，禁止推测或编造；
                3. 回答涉及具体字段、格式、规则时，引用原文表述，并在句末标注来源编号，格式如【来源1】。
                用简体中文回答，简洁直接。
                """;
    }

    private String userPrompt(String question, List<Map<String, Object>> hits) {
        StringBuilder sb = new StringBuilder("【召回资料】\n");
        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> h = hits.get(i);
            sb.append("【来源").append(i + 1).append("】")
              .append(h.get("docTitle")).append(" / ").append(h.get("sectionPath") == null ? "（无小节）" : h.get("sectionPath"))
              .append("\n").append(h.get("content")).append("\n\n");
        }
        sb.append("【问题】").append(question);
        return sb.toString();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignored) {
            // 客户端断开（用户关页面/取消）时 send 抛异常，静默结束即可
        }
    }
}
