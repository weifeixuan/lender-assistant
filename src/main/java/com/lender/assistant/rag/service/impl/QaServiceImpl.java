package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.util.ThreadPoolFactory;
import com.lender.assistant.conversation.model.AssistantMessageCompletion;
import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.ConversationLockRegistry;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.rag.service.QaService;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import com.lender.assistant.rag.vo.ChatTurn;
import com.lender.assistant.rag.vo.response.QaDoneResponse;
import com.lender.assistant.rag.vo.response.QaMetaResponse;
import com.lender.assistant.rag.vo.response.QaSourceResponse;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答编排（阶段 2 核心）：检索 → 拼 Prompt → 模型生成 → SSE 流式推给前端。
 *
 * Prompt 三铁律（RAG 的价值所在，面试必讲）：
 * 1. 只准依据召回内容回答——把模型的"自由发挥面"压缩到召回质量上，这是治幻觉的根；
 * 2. 召回内容不足时明确说"资料中未提及"，禁止编造——宁可答不出来，不可答错；
 * 3. 回答引用【来源 N】，为阶段 3 的引用溯源打底。
 *
 * SSE 事件协议（前端按 event 名分发）：
 * - meta:    JSON，{conversationId, roundId, userMessageId, assistantMessageId}（首问懒创建的唯一回传入口）
 * - sources: JSON 数组，本次召回的来源列表（先发，前端可先渲染"正在引用 N 份资料"）
 * - delta:   纯文本，模型 token 增量，前端增量拼接渲染
 * - done:    JSON，{provider, elapsedMillis, sourceCount}，收尾信号
 * - error:   纯文本，服务端异常信息（之后 completeWithError 断流）
 */
@Service
public class QaServiceImpl implements QaService {

    private static final Logger log = LoggerFactory.getLogger(QaServiceImpl.class);

    /** SSE 超时：覆盖"检索 + 模型生成全流程"，真实模型正常问答远快于这个值 */
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;
    /** 流式工作线程数：SSE 是长连接，固定小池避免无界膨胀 */
    private static final int STREAM_POOL_SIZE = 8;
    /** 有界等待队列容量：占满即快速失败，防止任务无界堆积 OOM（§8.1） */
    private static final int STREAM_QUEUE_CAPACITY = 100;
    private static final long STREAM_THREAD_KEEP_ALIVE_SECONDS = 60L;
    private static final String STREAM_THREAD_NAME_PREFIX = "qa-sse-worker";

    private final SearchService searchService;
    private final AnswerGeneratorStrategy generator;
    private final com.lender.assistant.resilience.ResilientLlmExecutor executor;
    private final ConversationService conversationService;
    private final ConversationLockRegistry lockRegistry;
    private final ConversationHistoryAssembler historyAssembler;

    /**
     * SSE 工作线程池：检索+生成+send 必须脱离请求线程执行，否则 Spring 会把事件缓冲到
     * 控制器返回时才一次性 flush（假流式 + Chrome net::ERR_ABORTED），详见 answer()。
     * 经 ThreadPoolFactory 装配：命名 daemon 线程 + 有界队列 + 明确拒绝策略（§8.1）；
     * 生命周期（应用关停时关闭）不交给工厂，留在本类 @PreDestroy。
     */
    private final ThreadPoolExecutor streamExecutor = ThreadPoolFactory.create(
            STREAM_POOL_SIZE, STREAM_POOL_SIZE, STREAM_QUEUE_CAPACITY,
            STREAM_THREAD_KEEP_ALIVE_SECONDS, STREAM_THREAD_NAME_PREFIX);

    public QaServiceImpl(SearchService searchService, AnswerGeneratorStrategy generator,
                         com.lender.assistant.resilience.ResilientLlmExecutor executor,
                         ConversationService conversationService,
                         ConversationLockRegistry lockRegistry,
                         ConversationHistoryAssembler historyAssembler) {
        this.searchService = searchService;
        this.generator = generator;
        this.executor = executor;
        this.conversationService = conversationService;
        this.lockRegistry = lockRegistry;
        this.historyAssembler = historyAssembler;
    }

    @Override
    public SseEmitter answer(String question, int topK, String conversationId, String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        long t0 = System.currentTimeMillis();

        // 首问懒创建 / 既有会话追问：都在请求线程内先落用户消息（DB 失败直接以普通 JSON 报错，不必开 SSE）。
        // 追问路径先取进程内会话锁，避免同一会话并发把历史写乱；锁由工作线程 finally 释放。
        ConversationRound round = conversationId == null
                ? conversationService.startNewRound(userId, question)
                : startFollowUpRound(conversationId, userId, question);

        // 关键：立即返回 emitter，把检索+生成+send 放到独立线程。
        // 旧写法在请求线程内 send，Spring 的 ResponseBodyEmitter 会把数据先堆进
        // earlySendAttempts（此时 handler 还未挂上），直到控制器返回才一次性写出——
        // 结果是 TTFB≈TOTAL 的"假流式"，且突发写入后立刻断连会让 Chrome 记 net::ERR_ABORTED。
        streamExecutor.execute(() -> {
            try {
                streamAnswer(emitter, question, topK, userId, round, t0);
            } finally {
                if (conversationId != null) {
                    lockRegistry.release(conversationId);
                }
            }
        });
        return emitter;
    }

    /** 追问受理：先占会话锁（失败 409、不写任何消息），再归属校验并落用户消息；失败路径必须释放锁 */
    private ConversationRound startFollowUpRound(String conversationId, String userId, String question) {
        if (!lockRegistry.tryAcquire(conversationId)) {
            throw new BizException(ErrorCode.CONVERSATION_BUSY);
        }
        try {
            return conversationService.startFollowUpRound(conversationId, userId, question);
        } catch (RuntimeException e) {
            lockRegistry.release(conversationId);
            throw e;
        }
    }

    /** SSE 推送主流程，运行在 streamExecutor 工作线程上；每个 send 都是即时 flush 的真流式。 */
    public void streamAnswer(SseEmitter emitter, String question, int topK, String userId,
                             ConversationRound round, long t0) {
        RoundStream stream = new RoundStream(round, t0);
        // 超时 / 连接异常 / 提前完成都按"用户停止或断连"落 STOPPED；
        // 正常路径先落 DONE 再 complete，因此这些回调不会覆盖终态。
        emitter.onTimeout(stream::stopped);
        emitter.onError(e -> stream.stopped());
        emitter.onCompletion(stream::stopped);

        try {
            // meta 必须首事件：前端靠它把本轮增量回答挂到正确的会话/轮次上
            send(emitter, "meta", new QaMetaResponse(round.conversationId(), round.roundId(),
                    round.userMessageId(), round.assistantMessageId()));

            // 历史只在追问时非空；检索 query 带最近一条历史用户提问，让"那二套呢"这类指代能召回正确资料
            List<ChatTurn> history = historyAssembler.assemble(round.conversationId(), round.roundId());
            List<SearchItemResponse> hits = searchService.hybridSearch(retrievalQuery(history, question), topK);
            List<QaSourceResponse> sources = hits.isEmpty() ? List.of() : buildSources(hits);
            stream.sources(sources);
            // 来源列表先发；未召回时也发空数组，前端协议保持单一形态
            send(emitter, "sources", sources);

            if (hits.isEmpty()) {
                String guidance = "资料库中没有检索到相关内容，请先在上方上传并入库文档。";
                stream.append(guidance);
                if (!send(emitter, "delta", guidance)) {
                    stream.stopped();
                }
                send(emitter, "done",
                        new QaDoneResponse(generator.name(), System.currentTimeMillis() - t0, 0));
                stream.done();
                emitter.complete();
                return;
            }

            streamModelAnswer(emitter, question, history, hits, stream);

            long elapsedMillis = System.currentTimeMillis() - t0;
            stream.done();

            QaDoneResponse done = new QaDoneResponse(generator.name(), elapsedMillis, sources.size());
            send(emitter, "done", done);
            emitter.complete();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // 熔断打开：连续失败触发保护，快速失败。降级而非崩溃（手册验收线）——
            // sources 已经推给前端，检索能力不受模型故障影响。
            // 记 WARN 不记 ERROR：这是熔断器的正常降级动作，但需有人从日志看到"模型在连续失败"
            log.warn("问答链路熔断打开，已降级为可读提示: {}", e.getMessage());
            send(emitter, "error", "模型服务熔断中（连续失败已触发保护），已转人工流程兜底；"
                    + "上方召回来源仍可查看。模型恢复前请使用检索测试区块。");
            stream.failed("模型服务熔断中");
            emitter.completeWithError(e);
        } catch (Exception e) {
            // 模型超时/网络异常/DB 异常统一走 error 事件——前端能拿到可读信息而非静默断流。
            // 必须带堆栈落 ERROR：SSE 已断流，这是排查链路上唯一的完整证据
            log.error("问答链路故障，已推送 error 事件并断流: question={}", question, e);
            send(emitter, "error", "问答失败：" + e.getMessage());
            stream.failed("问答失败：" + e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /** 召回结果 → 前端来源列表（idx 从 1 起，与【来源N】编号一致） */
    private List<QaSourceResponse> buildSources(List<SearchItemResponse> hits) {
        List<QaSourceResponse> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchItemResponse hit = hits.get(i);
            sources.add(new QaSourceResponse(i + 1, hit.docTitle(), hit.sectionPath(), hit.chunkId(),
                    hit.content(), hit.vectorDistance(), hit.keywordHits(), hit.bothChannels(), hit.rrfScore()));
        }
        return sources;
    }

    /**
     * 调模型并把 token 增量推给前端，同时累积完整回答用于落库。
     * AnswerGeneratorStrategy 契约：generate 返回时生成已结束，token 在调用线程上依次回调。
     * 韧性（阶段4）：经 ResilientLlmExecutor 走 qa 链路——留痕 + 熔断；
     * allowRetry=false：SSE token 已推给用户，重试会导致内容重放。
     */
    private void streamModelAnswer(SseEmitter emitter, String question, List<ChatTurn> history,
                                   List<SearchItemResponse> hits, RoundStream stream) {
        executor.execute(LlmScene.QA, generator.name(),
                () -> {
                    generator.generate(systemPrompt(), history, question, hits, token -> {
                        stream.append(token);
                        if (!send(emitter, "delta", token)) {
                            // 客户端已断开：按用户停止处理，不再尝试推送后续事件或 error
                            stream.stopped();
                        }
                    });
                    return true;
                }, false);
    }

    /** 检索 query：最近一条历史用户正文 + 换行 + 当前问题；无历史只用当前问题（不做 LLM 改写） */
    private static String retrievalQuery(List<ChatTurn> history, String question) {
        String lastUser = ConversationHistoryAssembler.lastUserContent(history);
        return lastUser == null ? question : lastUser + "\n" + question;
    }

    /** 单轮流式状态：累积正文与来源快照，并保证终态只落库一次 */
    private final class RoundStream {

        private final ConversationRound round;
        private final long startedAt;
        private final StringBuilder content = new StringBuilder();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private List<QaSourceResponse> sources = List.of();

        private RoundStream(ConversationRound round, long startedAt) {
            this.round = round;
            this.startedAt = startedAt;
        }

        synchronized void append(String token) {
            if (!finished.get()) {
                content.append(token);
            }
        }

        synchronized void sources(List<QaSourceResponse> value) {
            this.sources = value;
        }

        void done() {
            persist("DONE", null);
        }

        void failed(String error) {
            persist("FAILED", error);
        }

        void stopped() {
            persist("STOPPED", null);
        }

        private void persist(String status, String error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            String text;
            List<QaSourceResponse> snapshot;
            synchronized (this) {
                text = content.toString();
                snapshot = sources;
            }
            conversationService.completeRound(new AssistantMessageCompletion(
                    round.conversationId(), round.roundId(), round.assistantMessageId(),
                    text, snapshot, generator.name(), System.currentTimeMillis() - startedAt,
                    status, error));
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

    /** @return false 表示客户端已断开（调用方据此把本轮标记为 STOPPED） */
    private boolean send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (Exception e) {
            // 客户端断开（用户关页面/取消）时 send 抛异常，静默结束即可；
            // 记 DEBUG 留痕但不打扰——正常取消不该刷 ERROR 日志
            log.debug("SSE 事件发送失败，客户端可能已断开: event={}", event);
            return false;
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdownNow();
    }
}
