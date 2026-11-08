package com.lender.assistant.rag.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO;
import com.lender.assistant.conversation.model.dto.ConversationRoundDTO;
import com.lender.assistant.conversation.model.dto.QaSourceSnapshotDTO;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.rag.model.dto.AnswerCancellationDTO;
import com.lender.assistant.rag.model.dto.ExecutionPlanDTO;
import com.lender.assistant.rag.model.dto.ExecutionPlanSnapshotDTO;
import com.lender.assistant.rag.model.dto.PlanExecutionResultDTO;
import com.lender.assistant.rag.model.dto.RoutingDecisionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单轮流式状态：累积正文与来源快照，并保证终态只落库一次。
 *
 * <p>按轮 {@code new}（非 Spring Bean）；终态由 {@code finished} CAS 唯一闸门控制。</p>
 */
final class RoundStateServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(RoundStateServiceImpl.class);

    private final ConversationRoundDTO round;
    private final long startedAt;
    private final StringBuilder content = new StringBuilder();
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /** 客户端断开标志：置位后不再累积正文、不再推送任何事件 */
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    /**
     * 上游生成取消句柄：断开/超时的同一时刻 cancel，让模型侧停止消费 token，
     * 而不是等 generate 自然结束（否则白烧 token，且会话锁要多占一整段生成时间）。
     */
    private final AnswerCancellationDTO cancellation = new AnswerCancellationDTO();
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private List<QaSourceSnapshotDTO> sources = List.of();
    /**
     * 本轮 provider：meta 首事件先写规则名，模型真正开始生成时由 provider() 覆写。
     * volatile 保证工作线程的覆写对终态落库线程可见；落库时仍在 synchronized 内取快照。
     */
    private volatile String providerName;
    /** 本轮路由结果；mock 模式为 null */
    private RoutingDecisionDTO routeDecision;
    /** PLAN 轮的计划与执行快照；KNOWLEDGE / IRRELEVANT 轮为 null */
    private ExecutionPlanDTO executionPlan;
    private PlanExecutionResultDTO executionResult;
    /** 合成上下文是否被 6000 字符截断（票 09） */
    private boolean contextTruncated;

    RoundStateServiceImpl(ConversationRoundDTO round, long startedAt, String providerName,
                          ConversationService conversationService, ObjectMapper objectMapper) {
        this.round = round;
        this.startedAt = startedAt;
        this.providerName = providerName;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
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

    synchronized void sources(List<QaSourceSnapshotDTO> value) {
        this.sources = value;
    }

    synchronized void route(RoutingDecisionDTO decision) {
        this.routeDecision = decision;
    }

    synchronized void recordPlan(ExecutionPlanDTO plan, PlanExecutionResultDTO result) {
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

    AnswerCancellationDTO cancellation() {
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
        List<QaSourceSnapshotDTO> snapshot;
        String routeJson;
        String executionPlanJson;
        String providerSnapshot;
        synchronized (this) {
            text = content.toString();
            snapshot = sources;
            routeJson = writeJson(routeDecision);
            executionPlanJson = writeExecutionPlanJson();
            providerSnapshot = providerName;
        }
        conversationService.completeRound(new AssistantMessageCompletionDTO(
                round.conversationId(), round.roundId(), round.assistantMessageId(),
                text, snapshot, providerSnapshot, System.currentTimeMillis() - startedAt,
                status, error, routeJson, executionPlanJson));
    }

    /** execution_plan 快照：弃用计划只有头信息，执行过的计划带逐步结果 */
    private String writeExecutionPlanJson() {
        if (executionPlan == null) {
            return null;
        }
        return writeJson(ExecutionPlanSnapshotDTO.of(executionPlan, executionResult,
                new ExecutionPlanSnapshotDTO.SnapshotContext(providerName, elapsedMillis(),
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
