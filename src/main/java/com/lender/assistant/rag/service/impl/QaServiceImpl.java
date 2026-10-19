package com.lender.assistant.rag.service.impl;

import com.lender.assistant.rag.service.QaService;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class QaServiceImpl implements QaService {

    private final SearchService searchService;
    private final AnswerGeneratorStrategy generator;
    private final com.lender.assistant.resilience.ResilientLlmExecutor executor;

    /**
     * SSE 工作线程池：检索+生成+send 必须脱离请求线程执行，否则 Spring 会把事件缓冲到
     * 控制器返回时才一次性 flush（假流式 + Chrome net::ERR_ABORTED），详见 answer()。
     * daemon 线程不阻塞 JVM 退出；固定 8 线程——SSE 是长连接，避免无界膨胀。
     */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "qa-sse-worker");
        t.setDaemon(true);
        return t;
    });

    public QaServiceImpl(SearchService searchService, AnswerGeneratorStrategy generator,
                     com.lender.assistant.resilience.ResilientLlmExecutor executor) {
        this.searchService = searchService;
        this.generator = generator;
        this.executor = executor;
    }

    @Override
    public SseEmitter answer(String question, int topK) {
        // 120s 超时：覆盖"检索 + 模型生成全流程"，DeepSeek 正常问答远快于这个值
        SseEmitter emitter = new SseEmitter(120_000L);
        long t0 = System.currentTimeMillis();

        // 关键：立即返回 emitter，把检索+生成+send 放到独立线程。
        // 旧写法在请求线程内 send，Spring 的 ResponseBodyEmitter 会把数据先堆进
        // earlySendAttempts（此时 handler 还未挂上），直到控制器返回才一次性写出——
        // 结果是 TTFB≈TOTAL 的"假流式"，且突发写入后立刻断连会让 Chrome 记 net::ERR_ABORTED。
        streamExecutor.execute(() -> streamAnswer(emitter, question, topK, t0));
        return emitter;
    }

    /** SSE 推送主流程，运行在 streamExecutor 工作线程上；每个 send 都是即时 flush 的真流式。 */
    private void streamAnswer(SseEmitter emitter, String question, int topK, long t0) {
        try {
            List<Map<String, Object>> hits = searchService.hybridSearch(question, topK);
            if (hits.isEmpty()) {
                send(emitter, "delta", "资料库中没有检索到相关内容，请先在上方上传并入库文档。");
                emitter.complete();
                return;
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

            // AnswerGeneratorStrategy 契约：generate 返回时生成已结束，token 在调用线程上依次回调。
            // 韧性（阶段4）：经 ResilientLlmExecutor 走 qa 链路——留痕 + 熔断；
            // allowRetry=false：SSE token 已推给用户，重试会导致内容重放
            executor.execute("qa", generator.name(),
                    () -> {
                        generator.generate(systemPrompt(), userPrompt(question, hits),
                                token -> send(emitter, "delta", token));
                        return true;
                    }, false);

            Map<String, Object> done = Map.of(
                    "provider", generator.name(),
                    "elapsedMillis", System.currentTimeMillis() - t0,
                    "sourceCount", sources.size());
            send(emitter, "done", done);
            emitter.complete();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // 熔断打开：连续失败触发保护，快速失败。降级而非崩溃（手册验收线）——
            // sources 已经推给前端，检索能力不受模型故障影响
            send(emitter, "error", "模型服务熔断中（连续失败已触发保护），已转人工流程兜底；"
                    + "上方召回来源仍可查看。模型恢复前请使用检索测试区块。");
            emitter.completeWithError(e);
        } catch (Exception e) {
            // 模型超时/网络异常/DB 异常统一走 error 事件——前端能拿到可读信息而非静默断流
            send(emitter, "error", "问答失败：" + e.getMessage());
            emitter.completeWithError(e);
        }
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

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdownNow();
    }
}
