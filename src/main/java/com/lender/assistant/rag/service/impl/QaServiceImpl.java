package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.util.ThreadPoolFactory;
import com.lender.assistant.conversation.model.AssistantMessageCompletion;
import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.conversation.model.QaSourceSnapshot;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.ConversationLockRegistry;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.model.MessageRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.plan.PlanAndExecuteService;
import com.lender.assistant.rag.plan.PlanBudget;
import com.lender.assistant.rag.plan.PlanSourceAggregator;
import com.lender.assistant.rag.plan.SynthesisPromptBuilder;
import com.lender.assistant.rag.plan.model.ExecutionPlan;
import com.lender.assistant.rag.plan.model.ExecutionPlanSnapshot;
import com.lender.assistant.rag.plan.model.PlanExecutionResult;
import com.lender.assistant.rag.routing.PreviousRouteResolver;
import com.lender.assistant.rag.routing.QuestionRoutingService;
import com.lender.assistant.rag.routing.model.QuestionRoute;
import com.lender.assistant.rag.routing.model.RoutingDecision;
import com.lender.assistant.rag.routing.model.RoutingRequest;
import com.lender.assistant.rag.vo.response.QaPlanResponse;
import com.lender.assistant.rag.vo.response.QaRouteResponse;
import com.lender.assistant.rag.vo.response.QaStepResponse;
import com.lender.assistant.rag.service.QaService;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.strategy.AnswerCancellation;
import com.lender.assistant.rag.strategy.AnswerGenerationRequest;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import com.lender.assistant.rag.model.ChatTurn;
import com.lender.assistant.rag.vo.response.QaDoneResponse;
import com.lender.assistant.rag.vo.response.QaMetaResponse;
import com.lender.assistant.rag.vo.response.QaSourceResponse;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    /** 合成上限：实际取 min(60s, 剩余预算)（票 09） */
    private static final long SYNTHESIS_MAX_MILLIS = 60_000L;
    /**
     * 历史回答的效力约束：历史里的结论可能来自业务工具（如 customer.profile），
     * 本轮召回资料覆盖不到它不得反向否定——只就当前问题说"资料中未提及"。
     * 该句同时用于 KNOWLEDGE 与 SYNTHESIS 两个 system prompt。
     */
    private static final String HISTORY_ANSWER_CONSTRAINT =
            "补充约束：不得评判、否定、撤回或质疑历史轮回答的有效性；资料不足时只就当前问题"
                    + "说明\"资料中未提及\"，不得延伸到对历史回答的定性。";
    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            你是融资租赁资金方对接场景的资料问答助手。请基于「本轮目标」和「步骤产物」给出最终回答。
            铁律：
            1. 只依据步骤产物与召回资料回答，不得使用资料之外的任何知识；
            2. 资料不足以回答时，必须明确回答"资料中未提及"，禁止推测或编造；
            3. 引用资料时标注来源编号，格式如【来源1】，编号与 sources 一致。
            """ + HISTORY_ANSWER_CONSTRAINT + "\n" + """
            用简体中文回答，简洁直接。
            """;

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
    private final ThreadPoolExecutor streamExecutor;

    /** 合成超时计时器：只做一次定时取消，单线程守护线程，不参与业务执行 */
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "qa-synthesis-timer");
                thread.setDaemon(true);
                return thread;
            });

    /** 当前生成 provider；mock 模式跳过路由与计划，事件序列保持现状（票 03） */
    @Value("${rag.chat.provider:mock}")
    private String chatProvider;

    /** 无关问题的固定提示；非模型生成，done.provider=rule */
    @Value("${rag.routing.irrelevant-reply:我只回答资方接入相关的资料问题，可以换个问法再试试。}")
    private String irrelevantReply;

    /** 整轮预算：从路由开始计时到合成结束，唯一超时配置（票 10） */
    @Value("${rag.plan.total-budget-ms:90000}")
    private long totalBudgetMillis;

    @Autowired(required = false)
    private QuestionRoutingService routingService;

    @Autowired(required = false)
    private PreviousRouteResolver previousRouteResolver;

    @Autowired(required = false)
    private PlanAndExecuteService planningService;

    /** 仅用于落库 route / execution_plan 两个 JSONB 字段 */
    @Autowired(required = false)
    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public QaServiceImpl(SearchService searchService, AnswerGeneratorStrategy generator,
                         com.lender.assistant.resilience.ResilientLlmExecutor executor,
                         ConversationService conversationService,
                         ConversationLockRegistry lockRegistry,
                         ConversationHistoryAssembler historyAssembler) {
        this(searchService, generator, executor, conversationService, lockRegistry, historyAssembler,
                ThreadPoolFactory.create(STREAM_POOL_SIZE, STREAM_POOL_SIZE, STREAM_QUEUE_CAPACITY,
                        STREAM_THREAD_KEEP_ALIVE_SECONDS, STREAM_THREAD_NAME_PREFIX));
    }

    /** 测试可注入受控线程池（例如已 shutdown 的池，用于验证拒绝路径） */
    public QaServiceImpl(SearchService searchService, AnswerGeneratorStrategy generator,
                         com.lender.assistant.resilience.ResilientLlmExecutor executor,
                         ConversationService conversationService,
                         ConversationLockRegistry lockRegistry,
                         ConversationHistoryAssembler historyAssembler,
                         ThreadPoolExecutor streamExecutor) {
        this.searchService = searchService;
        this.generator = generator;
        this.executor = executor;
        this.conversationService = conversationService;
        this.lockRegistry = lockRegistry;
        this.historyAssembler = historyAssembler;
        this.streamExecutor = streamExecutor;
    }

    @Override
    public SseEmitter answer(String question, int topK, String conversationId, String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        long t0 = System.currentTimeMillis();

        // 首问懒创建 / 既有会话追问：都在请求线程内先落用户消息（DB 失败直接以普通 JSON 报错，不必开 SSE）。
        // 两条路径都先占同一把会话锁：首问的 conversationId 已随 meta 回传，必须从第一轮起就挡住并发追问。
        ConversationRound round = beginRound(conversationId, userId, question);

        // 关键：立即返回 emitter，把检索+生成+send 放到独立线程。
        // 旧写法在请求线程内 send，Spring 的 ResponseBodyEmitter 会把数据先堆进
        // earlySendAttempts（此时 handler 还未挂上），直到控制器返回才一次性写出——
        // 结果是 TTFB≈TOTAL 的"假流式"，且突发写入后立刻断连会让 Chrome 记 net::ERR_ABORTED。
        try {
            streamExecutor.execute(() -> {
                try {
                    streamAnswer(emitter, question, topK, userId, round, t0);
                } finally {
                    lockRegistry.release(round.conversationId());
                }
            });
        } catch (RuntimeException e) {
            // 线程池饱和/已关闭：不留半开 SSE，落一条 FAILED 兜底后按 500 普通 JSON 返回
            log.error("SSE 任务提交失败，已落 FAILED 兜底: conversationId={}", round.conversationId(), e);
            lockRegistry.release(round.conversationId());
            // 模型从未运行：provider 按 PRD §2.5 为 null；诊断信息只进日志（见上一行），不写进用户可见的 error
            conversationService.completeRound(new AssistantMessageCompletion(
                    round.conversationId(), round.roundId(), round.assistantMessageId(),
                    "", List.of(), null, 0, MessageConstants.STATUS_FAILED,
                    "服务繁忙，请重试", null, null));
            throw new BizException(ErrorCode.INTERNAL_ERROR, "服务繁忙，请重试");
        }
        return emitter;
    }

    /**
     * 受理一轮：生成四项标识（PRD §5.4 归 QaService）→ 占锁 → 落用户消息。
     *
     * 归属校验必须先于占锁：他人会话/已删除会话返回 404，不能被误报成 409，也不能触碰他人的锁。
     * 占锁后落库失败要立刻释放，避免这一轮把会话永久锁死。
     */
    private ConversationRound beginRound(String conversationId, String userId, String question) {
        boolean firstQuestion = conversationId == null;
        if (!firstQuestion) {
            conversationService.validateOwnership(conversationId, userId);
        }
        ConversationRound round = new ConversationRound(
                firstQuestion ? UUID.randomUUID().toString() : conversationId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        if (!lockRegistry.tryAcquire(round.conversationId())) {
            throw new BizException(ErrorCode.CONVERSATION_BUSY);
        }
        try {
            if (firstQuestion) {
                conversationService.startNewRound(round, userId, question);
            } else {
                conversationService.startFollowUpRound(round, userId, question);
            }
            return round;
        } catch (RuntimeException e) {
            lockRegistry.release(round.conversationId());
            throw e;
        }
    }

    /** SSE 推送主流程，运行在 streamExecutor 工作线程上；每个 send 都是即时 flush 的真流式。 */
    public void streamAnswer(SseEmitter emitter, String question, int topK, String userId,
                             ConversationRound round, long t0) {
        RoundStream stream = new RoundStream(round, t0);
        // 超时 / 连接异常 / 提前完成都置位"客户端已断开"并落 STOPPED；
        // 正常路径先落 DONE 再 complete，因此这些回调不会覆盖终态。
        emitter.onTimeout(stream::timeout);
        emitter.onError(e -> stream.disconnected());
        emitter.onCompletion(stream::disconnected);

        try {
            // meta 必须首事件：前端靠它把本轮增量回答挂到正确的会话/轮次上
            if (!send(emitter, "meta", new QaMetaResponse(round.conversationId(), round.roundId(),
                    round.userMessageId(), round.assistantMessageId()), stream)) {
                return;
            }

            // 历史只在追问时非空；检索 query 带最近一条历史用户提问，让"那二套呢"这类指代能召回正确资料
            List<ChatTurn> history = historyAssembler.assemble(round.conversationId(), round.roundId());
            // 追问时把「当前问题」与「上一轮问题」分段传给检索：关键词 token 配额按段分配，
            // 避免上一轮长问题按顺序把当前追问的关键词挤出配额（票 08）
            String lastUser = ConversationHistoryAssembler.lastUserContent(history);
            // 预算从路由开始计时：路由、计划、步骤、合成共用这 90s（票 10）
            RoundRequest request = new RoundRequest(question, lastUser, topK, round, stream,
                    new PlanBudget(totalBudgetMillis, System::currentTimeMillis));
            PlanRoundOutcome planOutcome = null;
            if (!isMockMode()) {
                RoutingDecision decision = routeRound(emitter, request);
                if (decision == null) {
                    return;
                }
                if (decision.route() == QuestionRoute.IRRELEVANT) {
                    emitIrrelevant(emitter, stream);
                    return;
                }
                if (decision.route() == QuestionRoute.PLAN) {
                    planOutcome = runPlanRound(emitter, request);
                    if (stream.isDisconnected()) {
                        return;
                    }
                }
            }
            // PLAN 轮用多步并集命中；计划弃用或无计划服务时退回单次检索（KNOWLEDGE 兜底）
            List<SearchItemResponse> hits = planOutcome != null ? planOutcome.hits()
                    : searchService.hybridSearch(question, lastUser, topK);
            if (!pushSources(emitter, hits, stream)) {
                return;
            }

            if (planOutcome != null) {
                if (!synthesizePlan(emitter, request, planOutcome, hits)) {
                    return;
                }
            } else if (hits.isEmpty()) {
                emitNoRecall(emitter, stream);
                return;
            } else {
                streamModelAnswer(emitter, question, history, hits, stream);
            }

            long elapsedMillis = System.currentTimeMillis() - t0;
            stream.done();
            if (!send(emitter, "done",
                    new QaDoneResponse(generator.name(), elapsedMillis, hits.size()), stream)) {
                return;
            }
            emitter.complete();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            handleCircuitOpen(emitter, e, stream);
        } catch (Exception e) {
            handleFailure(emitter, question, e, stream);
        }
    }

    /**
     * 推送 route 事件并记录本轮路由快照。
     *
     * @return 路由结果；null 表示客户端已断开
     */
    private RoutingDecision routeRound(SseEmitter emitter, RoundRequest request) {
        if (routingService == null) {
            return new RoutingDecision(QuestionRoute.KNOWLEDGE, 0.0, "无路由服务", "NONE");
        }
        RoutingDecision decision;
        try {
            List<MessageRow> rows = historyAssembler.listRows(request.round().conversationId());
            QuestionRoute previous = previousRouteResolver == null
                    ? null : previousRouteResolver.resolve(rows);
            decision = routingService.route(
                    new RoutingRequest(request.question(), request.lastUser(), previous, ""));
        } catch (RuntimeException ex) {
            // 路由链路任何异常都 fail-open：降级 KNOWLEDGE，本轮仍按正常问答收尾（票 11）
            log.warn("路由失败，降级 KNOWLEDGE: {}", ex.getMessage());
            decision = new RoutingDecision(QuestionRoute.KNOWLEDGE, 0.0, "路由异常降级", "NONE");
        }
        if (!send(emitter, "route",
                new QaRouteResponse(decision.route().name(), decision.confidence()), request.stream())) {
            return null;
        }
        request.stream().route(decision);
        return decision;
    }

    /**
     * PLAN 轮：计划只发一次；校验通过后串行执行并逐步推 step 事件。
     *
     * @return 多步并集命中与执行快照；null 表示计划弃用/无计划服务，调用方退回 KNOWLEDGE 单次检索
     */
    private PlanRoundOutcome runPlanRound(SseEmitter emitter, RoundRequest request) {
        if (planningService == null) {
            return null;
        }
        ExecutionPlan plan = planningService.plan(request.question(), request.lastUser());
        if (!send(emitter, "plan", QaPlanResponse.of(plan), request.stream())) {
            return null;
        }
        if (plan.degraded()) {
            log.info("计划弃用，按 KNOWLEDGE 链路执行: question={}", request.question());
            return null;
        }
        PlanExecutionResult result = planningService.execute(plan, request.topK(), request.budget(),
                event -> send(emitter, "step", QaStepResponse.of(event), request.stream()));
        request.stream().executionPlan(plan, result);
        // 所有步骤结束后一次性并集，按 chunkId 去重；全局 idx 由 pushSources 连续分配
        return new PlanRoundOutcome(PlanSourceAggregator.union(result.steps()), plan, result,
                result.remainingBudgetMillis());
    }

    /**
     * PLAN 轮唯一一次生成：用「目标 + 步骤产物」合成，不重放完整历史。
     *
     * @return false 表示已推送无召回提示（无需再做合成）
     */
    private boolean synthesizePlan(SseEmitter emitter, RoundRequest request,
                                   PlanRoundOutcome outcome, List<SearchItemResponse> hits) {
        if (outcome.remainingBudgetMillis() <= 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "本轮处理超时，请重试");
        }
        SynthesisPromptBuilder.Context context = SynthesisPromptBuilder.build(
                request.question(), outcome.plan().goal(), outcome.result().steps());
        request.stream().contextTruncated(context.truncated());
        if (hits.isEmpty() && !context.businessOutput()) {
            emitNoRecall(emitter, request.stream());
            return false;
        }
        AtomicBoolean timedOut = new AtomicBoolean(false);
        long limit = Math.max(1, Math.min(SYNTHESIS_MAX_MILLIS, outcome.remainingBudgetMillis()));
        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
            timedOut.set(true);
            request.stream().cancellation().cancel();
        }, limit, TimeUnit.MILLISECONDS);
        try {
            streamSynthesis(emitter, request.stream(), context.prompt(), hits);
        } finally {
            timeoutTask.cancel(false);
        }
        if (timedOut.get()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "本轮处理超时，请重试");
        }
        return true;
    }

    /** 合成复用现有生成器，但只传合成上下文、空历史，scene 走 SYNTHESIS */
    private void streamSynthesis(SseEmitter emitter, RoundStream stream, String prompt,
                                 List<SearchItemResponse> hits) {
        executor.execute(LlmScene.SYNTHESIS, generator.name(),
                () -> {
                    generator.generate(new AnswerGenerationRequest(SYNTHESIS_SYSTEM_PROMPT, List.of(),
                            prompt, hits, token -> {
                                if (stream.isDisconnected()) {
                                    return;
                                }
                                stream.append(token);
                                send(emitter, "delta", token, stream);
                            }, stream.cancellation()));
                    return true;
                }, false);
    }

    /** PLAN 轮的检索并集与预算余量；用于合成阶段判断是否还有时间 */
    private record PlanRoundOutcome(List<SearchItemResponse> hits, ExecutionPlan plan,
                                    PlanExecutionResult result, long remainingBudgetMillis) {
    }

    /** 单轮请求参数：收敛参数个数，避免编排方法超过 4 个入参 */
    private record RoundRequest(String question, String lastUser, int topK,
                                ConversationRound round, RoundStream stream, PlanBudget budget) {
    }

    /** 无关问题：固定提示不检索不生成，sources 为空，provider=rule */
    private void emitIrrelevant(SseEmitter emitter, RoundStream stream) {
        stream.sources(List.of());
        if (!send(emitter, "sources", List.of(), stream)) {
            return;
        }
        stream.append(irrelevantReply);
        if (!send(emitter, "delta", irrelevantReply, stream)) {
            return;
        }
        stream.provider(MessageConstants.RULE_PROVIDER);
        stream.done();
        if (!send(emitter, "done", new QaDoneResponse(MessageConstants.RULE_PROVIDER,
                stream.elapsedMillis(), 0), stream)) {
            return;
        }
        emitter.complete();
    }

    /** mock 只保证对话能走通：跳过路由/计划，事件序列与现状一致 */
    private boolean isMockMode() {
        return chatProvider == null || "mock".equalsIgnoreCase(chatProvider);
    }

    /** 召回结果 → sources 事件；返回 false 表示客户端已断开（disconnected 已落 STOPPED） */
    private boolean pushSources(SseEmitter emitter, List<SearchItemResponse> hits, RoundStream stream) {
        List<QaSourceResponse> sources = hits.isEmpty() ? List.of() : buildSources(hits);
        // 落库用 conversation 包内的快照类型（JSON 同构），避免 conversation 反向依赖 rag 的响应 VO
        stream.sources(sources.stream().map(QaServiceImpl::toSnapshot).toList());
        // 来源列表先发；未召回时也发空数组，前端协议保持单一形态
        return send(emitter, "sources", sources, stream);
    }

    /** SSE 响应元素 → 落库快照，字段逐项对应（QaSourceSnapshot 与 QaSourceResponse 同构） */
    private static QaSourceSnapshot toSnapshot(QaSourceResponse source) {
        return new QaSourceSnapshot(source.idx(), source.docTitle(), source.sectionPath(),
                source.chunkId(), source.content(), source.vectorDistance(),
                source.keywordHits(), source.bothChannels(), source.rrfScore());
    }

    /** 无召回分支：推送引导文案与 done 后正常结束 */
    private void emitNoRecall(SseEmitter emitter, RoundStream stream) {
        String guidance = "资料库中没有检索到相关内容，请先在上方上传并入库文档。";
        stream.append(guidance);
        if (!send(emitter, "delta", guidance, stream)) {
            return;
        }
        if (!send(emitter, "done",
                new QaDoneResponse(generator.name(), stream.elapsedMillis(), 0), stream)) {
            return;
        }
        stream.done();
        emitter.complete();
    }

    /** 熔断打开：推送降级提示后断流，终态 FAILED；发送失败说明客户端已断开，不再重复收尾 */
    private void handleCircuitOpen(SseEmitter emitter,
                                   io.github.resilience4j.circuitbreaker.CallNotPermittedException e,
                                   RoundStream stream) {
        // 熔断打开：连续失败触发保护，快速失败。降级而非崩溃（手册验收线）——
        // sources 已经推给前端，检索能力不受模型故障影响。
        // 记 WARN 不记 ERROR：这是熔断器的正常降级动作，但需有人从日志看到"模型在连续失败"
        log.warn("问答链路熔断打开，已降级为可读提示: {}", e.getMessage());
        boolean sent = send(emitter, "error", "模型服务熔断中（连续失败已触发保护），已转人工流程兜底；"
                + "上方召回来源仍可查看。模型恢复前请使用检索测试区块。", stream);
        stream.failed("模型服务熔断中");
        if (sent) {
            emitter.completeWithError(e);
        }
    }

    /** 通用异常收尾：推送 error 事件后断流，保留已生成正文并按 FAILED 落库 */
    private void handleFailure(SseEmitter emitter, String question, Exception e, RoundStream stream) {
        // 模型超时/网络异常/DB 异常统一走 error 事件——前端能拿到可读信息而非静默断流。
        // 必须带堆栈落 ERROR：SSE 已断流，这是排查链路上唯一的完整证据
        log.error("问答链路故障，已推送 error 事件并断流: question={}", question, e);
        boolean sent = send(emitter, "error", "问答失败：" + e.getMessage(), stream);
        stream.failed("问答失败：" + e.getMessage());
        if (sent) {
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
                    generator.generate(new AnswerGenerationRequest(systemPrompt(), history, question, hits,
                            token -> {
                                // 已断开（超时/客户端取消）时不再累积，也不再尝试推送
                                if (stream.isDisconnected()) {
                                    return;
                                }
                                stream.append(token);
                                send(emitter, "delta", token, stream);
                            }, stream.cancellation()));
                    return true;
                }, false);
    }

    /** 单轮流式状态：累积正文与来源快照，并保证终态只落库一次 */
    private final class RoundStream {

        private final ConversationRound round;
        private final long startedAt;
        private final StringBuilder content = new StringBuilder();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        /** 客户端断开标志：置位后不再累积正文、不再推送任何事件 */
        private final AtomicBoolean disconnected = new AtomicBoolean(false);
        /**
         * 上游生成取消句柄：断开/超时的同一时刻 cancel，让模型侧停止消费 token，
         * 而不是等 generate 自然结束（否则白烧 token，且会话锁要多占一整段生成时间）。
         */
        private final AnswerCancellation cancellation = new AnswerCancellation();
        private List<QaSourceSnapshot> sources = List.of();
        private String providerName = QaServiceImpl.this.generator.name();
        /** 本轮路由结果；mock 模式为 null */
        private RoutingDecision routeDecision;
        /** PLAN 轮的计划与执行快照；KNOWLEDGE / IRRELEVANT 轮为 null */
        private ExecutionPlan executionPlan;
        private PlanExecutionResult executionResult;
        /** 合成上下文是否被 6000 字符截断（票 09） */
        private boolean contextTruncated;

        private RoundStream(ConversationRound round, long startedAt) {
            this.round = round;
            this.startedAt = startedAt;
        }

        long elapsedMillis() {
            return System.currentTimeMillis() - startedAt;
        }

        void provider(String value) {
            this.providerName = value;
        }

        synchronized void append(String token) {
            if (!finished.get()) {
                content.append(token);
            }
        }

        synchronized void sources(List<QaSourceSnapshot> value) {
            this.sources = value;
        }

        synchronized void route(RoutingDecision decision) {
            this.routeDecision = decision;
        }

        synchronized void executionPlan(ExecutionPlan plan, PlanExecutionResult result) {
            this.executionPlan = plan;
            this.executionResult = result;
        }

        synchronized void contextTruncated(boolean value) {
            this.contextTruncated = value;
        }

        void done() {
            persist(MessageConstants.STATUS_DONE, null);
        }

        boolean isDisconnected() {
            return disconnected.get();
        }

        AnswerCancellation cancellation() {
            return cancellation;
        }

        /** 生命周期回调（断开/超时）与发送失败统一走这里：取消上游、落 STOPPED */
        void disconnected() {
            disconnected.set(true);
            cancellation.cancel();
            stopped();
        }

        void timeout() {
            disconnected.set(true);
            cancellation.cancel();
            stopped();
        }

        void failed(String error) {
            persist(MessageConstants.STATUS_FAILED, error);
        }

        void stopped() {
            persist(MessageConstants.STATUS_STOPPED, null);
        }

        private void persist(String status, String error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            String text;
            List<QaSourceSnapshot> snapshot;
            String routeJson;
            String executionPlanJson;
            synchronized (this) {
                text = content.toString();
                snapshot = sources;
                routeJson = writeJson(routeDecision);
                executionPlanJson = writeExecutionPlanJson();
            }
            conversationService.completeRound(new AssistantMessageCompletion(
                    round.conversationId(), round.roundId(), round.assistantMessageId(),
                    text, snapshot, providerName, System.currentTimeMillis() - startedAt,
                    status, error, routeJson, executionPlanJson));
        }

        /** execution_plan 快照：弃用计划只有头信息，执行过的计划带逐步结果 */
        private String writeExecutionPlanJson() {
            if (executionPlan == null) {
                return null;
            }
            return writeJson(ExecutionPlanSnapshot.of(executionPlan, executionResult,
                    new ExecutionPlanSnapshot.SnapshotContext(providerName, elapsedMillis(),
                            contextTruncated, degradedReason())));
        }

        private String degradedReason() {
            if (executionPlan.degraded()) {
                return executionPlan.degradedReason();
            }
            return executionResult != null && executionResult.budgetExhausted()
                    ? "BUDGET_EXHAUSTED" : null;
        }

        private String writeJson(Object value) {
            if (value == null) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException ex) {
                log.warn("快照序列化失败，落库置空", ex);
                return null;
            }
        }
    }

    private String systemPrompt() {
        return """
                你是融资租赁资金方对接场景的资料问答助手。严格遵守三条铁律：
                1. 只依据下方提供的召回资料回答，不得使用资料之外的任何知识；
                2. 召回资料不足以回答问题时，必须明确回答"资料中未提及"，禁止推测或编造；
                3. 回答涉及具体字段、格式、规则时，引用原文表述，并在句末标注来源编号，格式如【来源1】。
                """ + HISTORY_ANSWER_CONSTRAINT + "\n" + """
                用简体中文回答，简洁直接。
                """;
    }

    /** @return false 表示客户端已断开或已置位断开标志（调用方无需重复处理） */
    private boolean send(SseEmitter emitter, String event, Object data, RoundStream stream) {
        if (stream.isDisconnected()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (Exception e) {
            // 客户端断开（用户关页面/取消）时 send 抛异常：置位断开、按 STOPPED 收尾，
            // 后续 token 不再推送；DEBUG 留痕即可，正常取消不该刷 ERROR 日志
            log.debug("SSE 事件发送失败，客户端可能已断开: event={}", event);
            stream.disconnected();
            return false;
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        timeoutScheduler.shutdownNow();
        streamExecutor.shutdownNow();
    }
}
