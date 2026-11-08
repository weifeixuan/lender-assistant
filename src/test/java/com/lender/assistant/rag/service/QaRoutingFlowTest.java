package com.lender.assistant.rag.service;

import com.lender.assistant.conversation.model.dto.ConversationRoundDTO;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.ConversationLockRegistry;
import com.lender.assistant.conversation.service.ConversationService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由接入编排的行为测试：事件序列与落库口径。
 * 路由分类本身的规则由 QuestionRoutingServiceImplTest 覆盖，这里只关心「怎么编排」。
 */
class QaRoutingFlowTest {

    private static final String USER_ID = "dev-user-001";

    private QaServiceImpl qaService;
    private FixedGenerator generator;
    private ConversationService conversationService;
    private ConversationHistoryAssembler historyAssembler;
    private QuestionRoutingService routing;
    private ConversationRoundDTO round;
    private final RecordingEmitter emitter = new RecordingEmitter();

    static class FixedGenerator implements AnswerGeneratorStrategy {
        private boolean mock;

        FixedGenerator mockMode() {
            this.mock = true;
            return this;
        }

        @Override
        public boolean isMock() {
            return mock;
        }

        @Override
        public void generate(AnswerGenerationRequestDTO request) {
            request.onDelta().accept("答案");
        }

        @Override
        public String name() {
            return "deepseek-chat";
        }
    }

    @BeforeEach
    void setUp() {
        var searchService = mock(com.lender.assistant.rag.service.SearchService.class);
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(new SearchItemResponse(1L, "doc", "标题", "节", "内容", null, null, false, null)));
        conversationService = mock(ConversationService.class);
        historyAssembler = mock(ConversationHistoryAssembler.class);
        when(historyAssembler.assemble(anyList(), anyString())).thenReturn(List.of());
        when(historyAssembler.listRows(anyString())).thenReturn(List.of());
        routing = mock(QuestionRoutingService.class);
        generator = new FixedGenerator();
        qaService = new QaServiceImpl(searchService, generator,
                new com.lender.assistant.resilience.ResilientLlmExecutor(
                        new com.lender.assistant.resilience.LlmCallLogger(
                                mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                        3, 10, 2, false),
                conversationService, new ConversationLockRegistry(), historyAssembler);
        ReflectionTestUtils.setField(qaService, "routingService", routing);
        ReflectionTestUtils.setField(qaService, "objectMapper",
                new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(qaService, "previousRouteResolver",
                new com.lender.assistant.rag.service.PreviousRouteResolver(new com.fasterxml.jackson.databind.ObjectMapper()));
        round = new ConversationRoundDTO("conv-1", "round-1", "msg-user", "msg-assistant");
    }

    @Test
    @DisplayName("一轮只查一次历史窗口：历史装配与上一轮路由解析复用同一批行")
    void historyWindowQueriedOncePerRound() {
        when(routing.route(any())).thenReturn(new RoutingDecisionDTO(
                QuestionRoute.KNOWLEDGE, 0.9, "资料问答", "NONE"));

        qaService.streamAnswer(emitter, "单笔代收限额", 4, USER_ID, round, System.currentTimeMillis());

        verify(historyAssembler, times(1)).listRows("conv-1");
        verify(historyAssembler, times(1)).assemble(anyList(), eq("round-1"));
    }

    @Test
    @DisplayName("无关问题：meta → route → sources([]) → delta → done，provider=rule 且不检索")
    void irrelevantRoundSendsFixedReply() {
        when(routing.route(any())).thenReturn(new RoutingDecisionDTO(
                QuestionRoute.IRRELEVANT, 0.95, "问候", "SMALL_TALK"));

        qaService.streamAnswer(emitter, "你好", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(List.of("event:meta", "event:route", "event:sources", "event:delta", "event:done"),
                emitter.eventNames());
        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("DONE")
                        && completion.provider().equals("rule")
                        && completion.sources().isEmpty()));
    }

    @Test
    @DisplayName("知识库问题：多一个 route 事件，其余与现状一致")
    void knowledgeRoundAddsRouteEvent() {
        when(routing.route(any())).thenReturn(new RoutingDecisionDTO(
                QuestionRoute.KNOWLEDGE, 0.9, "资料问答", "NONE"));

        qaService.streamAnswer(emitter, "单笔代收限额", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(List.of("event:meta", "event:route", "event:sources", "event:delta", "event:done"),
                emitter.eventNames());
        verify(conversationService).completeRound(argThat(completion ->
                completion.provider().equals("deepseek-chat")));
    }

    @Test
    @DisplayName("路由抛异常：降级 KNOWLEDGE，不发 error，消息 DONE")
    void routeFailureFallsBackToKnowledge() {
        when(routing.route(any())).thenThrow(new IllegalStateException("路由模型超时"));

        qaService.streamAnswer(emitter, "单笔代收限额", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(List.of("event:meta", "event:route", "event:sources", "event:delta", "event:done"),
                emitter.eventNames());
        verify(conversationService).completeRound(argThat(
                completion -> completion.status().equals("DONE")));
    }

    @Test
    @DisplayName("mock 生成策略跳过路由：不发 route/plan/step（按 Bean 能力判定，不看配置字符串）")
    void mockProviderSkipsRouting() {
        generator.mockMode();

        qaService.streamAnswer(emitter, "单笔代收限额", 4, USER_ID, round, System.currentTimeMillis());

        assertEquals(List.of("event:meta", "event:sources", "event:delta", "event:done"),
                emitter.eventNames());
        assertTrue(emitter.eventNames().stream().noneMatch("event:route"::equals));
        org.mockito.Mockito.verifyNoInteractions(routing);
    }

    static class RecordingEmitter extends SseEmitter {
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
