package com.lender.assistant.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.conversation.model.dto.ConversationRoundDTO;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.ConversationLockRegistry;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.rag.service.PlanAndExecuteService;
import com.lender.assistant.rag.service.StepListener;
import com.lender.assistant.rag.model.dto.ExecutionPlanDTO;
import com.lender.assistant.rag.model.dto.PlanExecutionResultDTO;
import com.lender.assistant.rag.model.dto.PlanStepDTO;
import com.lender.assistant.rag.model.dto.StepOutcomeDTO;
import com.lender.assistant.rag.model.dto.StepStatus;
import com.lender.assistant.rag.service.PreviousRouteResolver;
import com.lender.assistant.rag.service.QuestionRoutingService;
import com.lender.assistant.rag.model.dto.QuestionRoute;
import com.lender.assistant.rag.model.dto.RoutingDecisionDTO;
import com.lender.assistant.rag.service.impl.QaServiceImpl;
import com.lender.assistant.rag.model.dto.AnswerGenerationRequestDTO;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PLAN 轮合成编排：只生成一次、不重放完整历史、上下文截断与零命中兜底。
 */
class QaSynthesisFlowTest {

    private static final String USER_ID = "dev-user-001";

    private QaServiceImpl qaService;
    private ConversationService conversationService;
    private QuestionRoutingService routing;
    private PlanAndExecuteService planning;
    private CapturingGenerator generator;
    private ConversationRoundDTO round;
    private final SseRecordingEmitter emitter = new SseRecordingEmitter();
    /** completeRound 被调用那一刻已推送的事件名，用于证明"先落库再发 done" */
    private List<String> eventsAtPersist;

    @BeforeEach
    void setUp() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(source(9L)));
        conversationService = mock(ConversationService.class);
        doAnswer(invocation -> {
            eventsAtPersist = emitter.eventNames();
            return null;
        }).when(conversationService).completeRound(any());
        ConversationHistoryAssembler historyAssembler = mock(ConversationHistoryAssembler.class);
        when(historyAssembler.assemble(anyList(), anyString()))
                .thenReturn(List.of(new com.lender.assistant.rag.model.dto.ChatTurnDTO("USER", "上一轮问题")));
        when(historyAssembler.listRows(anyString())).thenReturn(List.of());
        routing = mock(QuestionRoutingService.class);
        when(routing.route(any())).thenReturn(new RoutingDecisionDTO(QuestionRoute.PLAN, 0.9, "多步", "NONE"));
        planning = mock(PlanAndExecuteService.class);
        generator = new CapturingGenerator();
        qaService = new QaServiceImpl(searchService, generator,
                new com.lender.assistant.resilience.ResilientLlmExecutor(
                        new com.lender.assistant.resilience.LlmCallLogger(
                                mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                        3, 10, 2, false),
                conversationService, new ConversationLockRegistry(), historyAssembler);
        ReflectionTestUtils.setField(qaService, "routingService", routing);
        ReflectionTestUtils.setField(qaService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(qaService, "planningService", planning);
        ReflectionTestUtils.setField(qaService, "previousRouteResolver",
                new PreviousRouteResolver(new ObjectMapper()));
        round = new ConversationRoundDTO("conv-1", "round-1", "msg-user", "msg-assistant");
    }

    @Test
    @DisplayName("PLAN 轮只调一次生成，历史为空，完成事件在 sources 之后")
    void planRoundGeneratesOnce() {
        ExecutionPlanDTO plan = twoStepPlan();
        PlanExecutionResultDTO result = new PlanExecutionResultDTO(List.of(
                outcome("s1", 1, "kb.search", StepStatus.DONE, List.of(source(1L)), "检索摘要"),
                outcome("s2", 2, "customer.profile", StepStatus.DONE, List.of(), "客户摘要")),
                false, 180_000);
        when(planning.plan(any(), any())).thenReturn(plan);
        stubExecute(result);

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(1, generator.requests.size());
        assertTrue(generator.requests.get(0).history().isEmpty(), "合成不重放完整历史");
        assertTrue(generator.requests.get(0).question().contains("本轮目标："));
        assertTrue(generator.requests.get(0).systemPrompt()
                        .contains("不得评判、否定、撤回或质疑历史轮回答的有效性"),
                "合成提示同样禁止用本轮资料否定历史工具结论");
        assertEquals(List.of("event:meta", "event:route", "event:plan",
                "event:step", "event:step", "event:step", "event:step",
                "event:sources", "event:delta", "event:done"), emitter.eventNames());
        verify(conversationService).completeRound(any());
    }

    @Test
    @DisplayName("零命中：不调生成，先落库再发 done，provider=rule")
    void zeroHitWithoutBusinessOutputSkipsGeneration() {
        ExecutionPlanDTO plan = twoStepPlan();
        PlanExecutionResultDTO result = new PlanExecutionResultDTO(List.of(
                outcome("s1", 1, "kb.search", StepStatus.DEGRADED, List.of(), "资料中未提及"),
                outcome("s2", 2, "kb.search", StepStatus.DEGRADED, List.of(), "资料中未提及")),
                false, 180_000);
        when(planning.plan(any(), any())).thenReturn(plan);
        stubExecute(result);

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(0, generator.requests.size());
        assertEquals(List.of("event:meta", "event:route", "event:plan",
                "event:step", "event:step", "event:step", "event:step",
                "event:sources", "event:delta", "event:done"), emitter.eventNames());
        assertFalse(eventsAtPersist.contains("event:done"), "done 事件必须在落库之后才发");
        assertEquals("rule", captured().provider(), "引导文案不是模型产出，provider 应为 rule");
    }

    @Test
    @DisplayName("零命中但业务工具有产物：仍短路到无召回提示，不调合成模型")
    void zeroHitWithBusinessOutputSkipsSynthesisModel() {
        ExecutionPlanDTO plan = twoStepPlan();
        PlanExecutionResultDTO result = new PlanExecutionResultDTO(List.of(
                outcome("s1", 1, "kb.search", StepStatus.DEGRADED, List.of(), "资料中未提及"),
                outcome("s2", 2, "customer.profile", StepStatus.DONE, List.of(), "可用额度 800 万")),
                false, 180_000);
        when(planning.plan(any(), any())).thenReturn(plan);
        stubExecute(result);

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(0, generator.requests.size(), "没有可引用来源时不调合成模型");
        assertEquals(List.of("event:meta", "event:route", "event:plan",
                "event:step", "event:step", "event:step", "event:step",
                "event:sources", "event:delta", "event:done"), emitter.eventNames());
        assertFalse(eventsAtPersist.contains("event:done"), "done 事件必须在落库之后才发");
        assertEquals("rule", captured().provider());
    }

    @Test
    @DisplayName("剩余预算耗尽：不发合成，推 error 并以 FAILED 落库")
    void zeroRemainingBudgetFailsRound() {
        ExecutionPlanDTO plan = twoStepPlan();
        PlanExecutionResultDTO result = new PlanExecutionResultDTO(List.of(
                outcome("s1", 1, "kb.search", StepStatus.DONE, List.of(source(1L)), "检索摘要"),
                outcome("s2", 2, "customer.profile", StepStatus.DONE, List.of(), "客户摘要")),
                true, 0L);
        when(planning.plan(any(), any())).thenReturn(plan);
        stubExecute(result);

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(0, generator.requests.size());
        assertTrue(emitter.eventNames().contains("event:error"));
        org.mockito.ArgumentCaptor<com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO>
                captor = org.mockito.ArgumentCaptor.forClass(
                com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO.class);
        verify(conversationService).completeRound(captor.capture());
        assertEquals("FAILED", captor.getValue().status());
    }

    @Test
    @DisplayName("合成异常：error + FAILED，保留已推正文，不出现 done")
    void synthesisFailureEmitsErrorAndFailed() {
        ExecutionPlanDTO plan = twoStepPlan();
        PlanExecutionResultDTO result = new PlanExecutionResultDTO(List.of(
                outcome("s1", 1, "kb.search", StepStatus.DONE, List.of(source(1L)), "检索摘要"),
                outcome("s2", 2, "customer.profile", StepStatus.DONE, List.of(), "客户摘要")),
                false, 180_000);
        when(planning.plan(any(), any())).thenReturn(plan);
        stubExecute(result);
        generator.failure = new IllegalStateException("合成失败");

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertTrue(emitter.eventNames().contains("event:error"));
        assertFalse(emitter.eventNames().contains("event:done"));
        com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO completion = captured();
        assertEquals("FAILED", completion.status());
        assertTrue(completion.content().contains("合成答案"), "已推正文必须保留");
    }

    @Test
    @DisplayName("合成超时：取消后按 error + FAILED 收尾")
    void synthesisTimeoutEmitsErrorAndFailed() {
        ExecutionPlanDTO plan = twoStepPlan();
        PlanExecutionResultDTO result = new PlanExecutionResultDTO(List.of(
                outcome("s1", 1, "kb.search", StepStatus.DONE, List.of(source(1L)), "检索摘要"),
                outcome("s2", 2, "customer.profile", StepStatus.DONE, List.of(), "客户摘要")),
                false, 1L);
        when(planning.plan(any(), any())).thenReturn(plan);
        stubExecute(result);
        generator.delayMillis = 100;

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertTrue(emitter.eventNames().contains("event:error"));
        assertFalse(emitter.eventNames().contains("event:done"));
        assertEquals("FAILED", captured().status());
    }

    private com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO captured() {
        org.mockito.ArgumentCaptor<com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO>
                captor = org.mockito.ArgumentCaptor.forClass(
                com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO.class);
        verify(conversationService).completeRound(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("计划弃用：不发 step，退回 KNOWLEDGE 单次生成")
    void degradedPlanFallsBackToKnowledge() {
        when(planning.plan(any(), any())).thenReturn(ExecutionPlanDTO.invalid());

        qaService.streamAnswer(emitter, "华信能放多少", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(1, generator.requests.size());
        verify(planning, never()).execute(any(), anyInt(), any(), any());
        assertTrue(emitter.eventNames().contains("event:plan"));
    }

    private void stubExecute(PlanExecutionResultDTO result) {
        when(planning.execute(any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            StepListener listener = invocation.getArgument(3);
            for (StepOutcomeDTO step : result.steps()) {
                listener.onStep(new com.lender.assistant.rag.model.dto.StepEventDTO(
                        step.stepId(), StepStatus.RUNNING, 0));
                listener.onStep(new com.lender.assistant.rag.model.dto.StepEventDTO(
                        step.stepId(), step.status(), step.elapsedMillis()));
            }
            return result;
        });
    }

    private static ExecutionPlanDTO twoStepPlan() {
        return ExecutionPlanDTO.valid("华信额度", List.of(
                new PlanStepDTO("s1", 1, "检索", "kb.search", Map.of("query", "华信 限额")),
                new PlanStepDTO("s2", 2, "查客户", "customer.profile", Map.of("customerId", "C10001"))));
    }

    private static StepOutcomeDTO outcome(String stepId, int order, String tool, StepStatus status,
                                       List<SearchItemResponse> sources, String summary) {
        return new StepOutcomeDTO(stepId, order, "步骤", tool, Map.of(), status, 10,
                sources.stream().map(SearchItemResponse::chunkId).toList(), sources, summary, null);
    }

    private static SearchItemResponse source(long chunkId) {
        return new SearchItemResponse(chunkId, "doc", "标题", "节", "内容", null, null, false, null);
    }

    static class CapturingGenerator implements AnswerGeneratorStrategy {
        final List<AnswerGenerationRequestDTO> requests = new ArrayList<>();
        RuntimeException failure;
        long delayMillis;

        @Override
        public void generate(AnswerGenerationRequestDTO request) {
            requests.add(request);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            request.onDelta().accept("合成答案");
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public String name() {
            return "deepseek-chat";
        }
    }

    static class SseRecordingEmitter extends SseEmitter {
        final List<Object> payloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) {
            builder.build().forEach(data -> payloads.add(data.getData()));
        }

        List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (Object payload : payloads) {
                if (payload instanceof String text) {
                    for (String line : text.split("\n")) {
                        if (line.startsWith("event:")) {
                            names.add(line);
                        }
                    }
                }
            }
            return names;
        }
    }
}
