package com.lender.assistant.rag.service;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.ConversationLockRegistry;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.rag.vo.ChatTurn;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.service.impl.QaServiceImpl;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 开工前的测试基线：锁住 QaService → AnswerGeneratorStrategy 的入参契约。
 *
 * 测的是公共契约而非私有方法：QaService.answer 内部把 systemPrompt、问题与召回结果
 * 交给 AnswerGeneratorStrategy——用桩生成器把入参抓出来断言。
 * Prompt 三铁律和结构化召回顺序是模型行为的根基，改动前必须先有这道护栏。
 *
 * 异步契约（2026-10-17 SSE 真流式改造后）：answer() 立即 return emitter，检索/生成/send
 * 在独立工作线程执行——故所有断言都必须用 awaitAsyncDone 等流程真正跑完，不能按"同步返回即已推完"写。
 */
class QaServiceTest {

    private static final String USER_ID = "dev-user-001";

    private SearchService searchService;
    private ConversationService conversationService;
    private ConversationLockRegistry lockRegistry;
    private ConversationHistoryAssembler historyAssembler;
    private com.lender.assistant.resilience.ResilientLlmExecutor resilientExecutor;
    /** 桩生成器：抓取收到的 system Prompt、问题与召回结果，并回流一个固定 token */
    private RecordingGenerator generator;
    private QaServiceImpl qaService;
    private ConversationRound round;

    /** 测试专用 AnswerGeneratorStrategy：记录入参，不做任何模型调用 */
    static class RecordingGenerator implements AnswerGeneratorStrategy {
        String system;
        String question;
        List<ChatTurn> history;
        List<SearchItemResponse> contextHits;
        final List<String> deltas = new java.util.ArrayList<>();

        @Override
        public void generate(String systemPrompt, List<ChatTurn> history, String question,
                             List<SearchItemResponse> contextHits, Consumer<String> onDelta,
                             com.lender.assistant.rag.strategy.AnswerCancellation cancellation) {
            this.system = systemPrompt;
            this.history = history;
            this.question = question;
            this.contextHits = contextHits;
            onDelta.accept("测试答案片段");
        }

        @Override
        public String name() {
            return "RecordingMock";
        }
    }

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        conversationService = mock(ConversationService.class);
        lockRegistry = new ConversationLockRegistry();
        historyAssembler = mock(ConversationHistoryAssembler.class);
        generator = new RecordingGenerator();
        // 真实韧性执行器（chaos=false 直通）+ mock JDBC 的留痕器
        resilientExecutor = new com.lender.assistant.resilience.ResilientLlmExecutor(
                new com.lender.assistant.resilience.LlmCallLogger(
                        mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                3, 10, 2, false);
        qaService = new QaServiceImpl(searchService, generator, resilientExecutor, conversationService,
                lockRegistry, historyAssembler);
        round = new ConversationRound("conv-1", "round-1", "msg-user", "msg-assistant");
        when(historyAssembler.assemble(anyString(), anyString())).thenReturn(List.of());
    }

    private SearchItemResponse hit(long chunkId, String title, String section, String content) {
        return new SearchItemResponse(chunkId, "doc1", title, section, content, null, null, false, null);
    }

    /** 阻塞等待异步 SSE 流程达成条件（answer() 已在独立线程执行），超时即判失败。 */
    private static void awaitAsyncDone(BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("等待 SSE 异步流程结束超时（5s）");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断");
            }
        }
    }

    @Test
    @DisplayName("systemPrompt 包含三条铁律的核心约束")
    void systemPromptContainsThreeRules() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "华信银行_日末文件格式说明", "3 逾期文件", "逾期阶段 M1/M2/M3")));

        qaService.answer("逾期阶段怎么划分", 4, null, USER_ID);
        awaitAsyncDone(() -> generator.system != null);   // answer() 已异步：等生成器真正被调用

        assertNotNull(generator.system);
        assertTrue(generator.system.contains("三条铁律"));
        assertTrue(generator.system.contains("资料中未提及"), "铁律2：不足时明说，禁止编造");
        assertTrue(generator.system.contains("来源"), "铁律3：引用溯源格式要求");
    }

    @Test
    @DisplayName("问题与召回结果按原顺序结构化传给生成器（【来源N】编号由实现方按顺序拼）")
    void passesQuestionAndContextHitsInRecallOrder() {
        when(searchService.hybridSearch(any(), any(), anyInt())).thenReturn(List.of(
                hit(1, "华信银行_日末文件格式说明", "3 逾期文件", "逾期阶段 M1 为 1-30 天"),
                hit(2, "华信银行_对公收付接口文档", "2 单笔代收接口", "单笔限额 500 万元")));

        qaService.answer("逾期阶段怎么划分", 4, null, USER_ID);
        awaitAsyncDone(() -> generator.contextHits != null);   // answer() 已异步：等生成器真正被调用

        assertEquals("逾期阶段怎么划分", generator.question);
        assertNotNull(generator.contextHits);
        assertEquals(2, generator.contextHits.size(), "两条召回都需传给生成器");
        assertEquals(1L, generator.contextHits.get(0).chunkId(), "来源顺序必须与召回顺序一致");
        assertEquals("3 逾期文件", generator.contextHits.get(0).sectionPath());
        assertEquals(2L, generator.contextHits.get(1).chunkId());
    }

    @Test
    @DisplayName("检索无结果：不调用生成器，直接给出引导文案")
    void emptyHitsSkipsGenerator() {
        when(searchService.hybridSearch(any(), any(), anyInt())).thenReturn(List.of());

        qaService.answer("随便问", 4, null, USER_ID);
        // 等异步流程确认 hybridSearch 确实被调用过，再断言生成器全程未被调用
        awaitAsyncDone(() -> {
            try { verify(searchService).hybridSearch("随便问", null, 4); return true; }
            catch (AssertionError notYet) { return false; }
        });

        assertEquals(null, generator.system, "生成器不应被调用");
    }

    @Test
    @DisplayName("生成器抛异常：QaService 不外泄异常（error 事件在 emitter 内处理）")
    void generatorExceptionDoesNotPropagate() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));
        AnswerGeneratorStrategy broken = new AnswerGeneratorStrategy() {
            @Override
            public void generate(String systemPrompt, List<ChatTurn> history, String question,
                                 List<SearchItemResponse> contextHits, Consumer<String> onDelta,
                                 com.lender.assistant.rag.strategy.AnswerCancellation cancellation) {
                throw new RuntimeException("模拟模型超时");
            }

            @Override
            public String name() {
                return "Broken";
            }
        };
        QaService svc = new QaServiceImpl(searchService, broken,
                new com.lender.assistant.resilience.ResilientLlmExecutor(
                        new com.lender.assistant.resilience.LlmCallLogger(
                                mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                        3, 10, 2, false), conversationService, lockRegistry, historyAssembler);

        assertDoesNotThrow(() -> svc.answer("问题", 4, null, USER_ID),
                "阶段4韧性改造的契约底线：模型故障不允许把异常抛出 answer() 之外");
        // 生成器异常发生在工作线程内：等异步流程跑到检索这一步即证明异常未外泄
        awaitAsyncDone(() -> {
            try { verify(searchService).hybridSearch(any(), any(), anyInt()); return true; }
            catch (AssertionError notYet) { return false; }
        });
    }

    /** 记录 SSE 事件名，避免真实 HTTP 写出；用于断言 meta 必须是首事件 */
    static class RecordingEmitter extends SseEmitter {
        final List<Object> payloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) {
            builder.build().forEach(data -> payloads.add(data.getData()));
        }

        /** 事件名可能和 "data:" 前缀拼在同一个字符串里，按换行拆出所有 event: 行 */
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

    /** 模拟客户端在生成中途断开：前 sendsAllowed 次发送成功，之后抛出 IOException */
    static class FailAfterEmitter extends SseEmitter {
        private int sendsAllowed;

        FailAfterEmitter(int sendsAllowed) {
            this.sendsAllowed = sendsAllowed;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (sendsAllowed-- <= 0) {
                throw new IOException("client gone");
            }
        }
    }

    /** 捕获超时回调，测试里手动触发（Spring 容器的超时由真实连接驱动） */
    static class CallbackEmitter extends SseEmitter {
        Runnable timeoutAction;

        @Override
        public void onTimeout(Runnable callback) {
            this.timeoutAction = callback;
        }
    }

    @Test
    @DisplayName("meta 是流的第一个事件，之后才推送 sources/delta/done")
    void metaIsFirstEvent() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));
        RecordingEmitter emitter = new RecordingEmitter();

        qaService.streamAnswer(emitter, "问题", 4, USER_ID, round, System.currentTimeMillis());

        List<String> names = emitter.eventNames();
        assertEquals("event:meta", names.get(0));
        assertTrue(names.contains("event:sources"));
        assertTrue(names.contains("event:delta"));
        assertTrue(names.contains("event:done"));
    }

    @Test
    @DisplayName("meta 发送失败：不再装配历史与检索，直接按 STOPPED 收尾")
    void metaSendFailureSkipsRetrieval() {
        qaService.streamAnswer(new FailAfterEmitter(0), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(searchService, never()).hybridSearch(any(), any(), anyInt());
        verify(historyAssembler, never()).assemble(anyString(), anyString());
        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("STOPPED")
                        && completion.content().isEmpty()));
    }

    @Test
    @DisplayName("正常结束把完整回答、来源与模型名落库")
    void persistsAssistantOnSuccess() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));

        qaService.streamAnswer(new RecordingEmitter(), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(conversationService).completeRound(argThat(completion ->
                completion.conversationId().equals("conv-1")
                        && completion.roundId().equals("round-1")
                        && completion.messageId().equals("msg-assistant")
                        && completion.content().equals("测试答案片段")
                        && completion.sources().size() == 1
                        && completion.provider().equals("RecordingMock")
                        && completion.status().equals("DONE")
                        && completion.error() == null));
    }

    @Test
    @DisplayName("检索无结果时把引导文案以空来源落库")
    void persistsGuidanceWhenNoHits() {
        when(searchService.hybridSearch(any(), any(), anyInt())).thenReturn(List.of());

        qaService.streamAnswer(new RecordingEmitter(), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(conversationService).completeRound(argThat(completion ->
                completion.content().contains("没有检索到相关内容")
                        && completion.sources().isEmpty()
                        && completion.status().equals("DONE")));
        assertNull(generator.system, "无召回不应调用生成器");
    }

    @Test
    @DisplayName("未召回时 sources 事件是空数组，delta 与 done 照常推送")
    void emptyHitsStillSendSourcesDeltaAndDone() {
        when(searchService.hybridSearch(any(), any(), anyInt())).thenReturn(List.of());
        RecordingEmitter emitter = new RecordingEmitter();

        qaService.streamAnswer(emitter, "问题", 4, USER_ID, round, System.currentTimeMillis());

        List<String> names = emitter.eventNames();
        assertEquals(List.of("event:meta", "event:sources", "event:delta", "event:done"), names);
    }

    @Test
    @DisplayName("模型异常：保留已生成正文，按 FAILED 落库且 error 非空")
    void persistsFailedRoundOnModelException() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));
        AnswerGeneratorStrategy broken = new AnswerGeneratorStrategy() {
            @Override
            public void generate(String systemPrompt, List<ChatTurn> history, String question,
                                 List<SearchItemResponse> contextHits, Consumer<String> onDelta,
                                 com.lender.assistant.rag.strategy.AnswerCancellation cancellation) {
                onDelta.accept("已生成的一半");
                throw new RuntimeException("模拟模型超时");
            }

            @Override
            public String name() {
                return "Broken";
            }
        };
        QaServiceImpl svc = new QaServiceImpl(searchService, broken,
                new com.lender.assistant.resilience.ResilientLlmExecutor(
                        new com.lender.assistant.resilience.LlmCallLogger(
                                mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                        3, 10, 2, false), conversationService, lockRegistry, historyAssembler);

        svc.streamAnswer(new RecordingEmitter(), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("FAILED")
                        && completion.content().equals("已生成的一半")
                        && completion.error() != null
                        && completion.sources().size() == 1));
    }

    @Test
    @DisplayName("客户端断开：保留已生成正文，按 STOPPED 落库且不推 error")
    void persistsStoppedRoundWhenClientDisconnects() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));

        qaService.streamAnswer(new FailAfterEmitter(2), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("STOPPED")
                        && completion.content().equals("测试答案片段")
                        && completion.error() == null));
    }

    @Test
    @DisplayName("客户端断开：立即取消上游生成，不再消费剩余 token（而不是等自然结束）")
    void clientDisconnectCancelsUpstreamGeneration() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));
        java.util.concurrent.atomic.AtomicInteger emitted = new java.util.concurrent.atomic.AtomicInteger();
        AnswerGeneratorStrategy longRunning = new AnswerGeneratorStrategy() {
            @Override
            public void generate(String systemPrompt, List<ChatTurn> history, String question,
                                 List<SearchItemResponse> contextHits, Consumer<String> onDelta,
                                 com.lender.assistant.rag.strategy.AnswerCancellation cancellation) {
                // 150 片 × 20ms ≈ 3s：这是"不取消就会一直烧 token 到自然结束"的基线
                for (int i = 0; i < 150; i++) {
                    if (cancellation.isCancelled()) {
                        return;
                    }
                    onDelta.accept("片段" + i);
                    emitted.incrementAndGet();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            @Override
            public String name() {
                return "LongRunning";
            }
        };
        QaServiceImpl svc = new QaServiceImpl(searchService, longRunning, resilientExecutor,
                conversationService, lockRegistry, historyAssembler);

        long start = System.currentTimeMillis();
        // meta + sources + 第一片成功，第二片 send 抛 IOException → 模拟客户端 abort
        svc.streamAnswer(new FailAfterEmitter(3), "问题", 4, USER_ID, round, start);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1000,
                "断开后应立刻停止消费上游，实际耗时 " + elapsed + "ms（自然结束约 3000ms）");
        assertTrue(emitted.get() <= 3,
                "取消后不应再消费 token，实际消费 " + emitted.get() + " 片");
        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("STOPPED")
                        && completion.content().startsWith("片段0")
                        && completion.error() == null));
    }

    @Test
    @DisplayName("追问：取锁后追加一轮；流进行中再问同一会话返回 409，且不写消息")
    void followUpLocksConversationWhileStreaming() throws Exception {
        CountDownLatch searching = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(searchService.hybridSearch(any(), any(), anyInt())).thenAnswer(invocation -> {
            searching.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS), "测试等待超时");
            return List.of();
        });

        qaService.answer("那二套呢", 4, "conv-1", USER_ID);
        awaitAsyncDone(() -> searching.getCount() == 0);

        BizException busy = assertThrows(BizException.class,
                () -> qaService.answer("再问一次", 4, "conv-1", USER_ID));
        assertEquals(ErrorCode.CONVERSATION_BUSY, busy.errorCode());
        verify(conversationService, times(1)).startFollowUpRound(any(), eq(USER_ID), any());

        resume.countDown();
    }

    @Test
    @DisplayName("流结束后释放会话锁，同一会话可以再次提问")
    void releasesLockAfterStream() throws Exception {
        CountDownLatch searching = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(searchService.hybridSearch(any(), any(), anyInt())).thenAnswer(invocation -> {
            searching.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS), "测试等待超时");
            return List.of();
        });

        qaService.answer("第一问", 4, "conv-1", USER_ID);
        awaitAsyncDone(() -> searching.getCount() == 0);
        assertTrue(lockRegistry.isBusy("conv-1"), "流进行中会话应被占用");

        resume.countDown();
        awaitAsyncDone(() -> !lockRegistry.isBusy("conv-1"));

        when(searchService.hybridSearch(any(), any(), anyInt())).thenReturn(List.of());
        assertDoesNotThrow(() -> qaService.answer("第二问", 4, "conv-1", USER_ID),
                "上一轮结束后锁必须已释放");
    }

    @Test
    @DisplayName("追问的会话不存在/非本人：归属校验返回 404 且不占锁")
    void followUpOnInvisibleConversationReleasesLock() {
        doThrow(new BizException(ErrorCode.CONVERSATION_NOT_FOUND))
                .when(conversationService).validateOwnership("conv-1", USER_ID);

        BizException exception = assertThrows(BizException.class,
                () -> qaService.answer("问题", 4, "conv-1", USER_ID));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.errorCode());
        assertFalse(lockRegistry.isBusy("conv-1"));
        verify(conversationService, never()).startFollowUpRound(any(), any(), any());
    }

    @Test
    @DisplayName("首问生成中同一会话追问 → 409（首问同样占锁）")
    void followUpDuringFirstAnswerReturnsBusy() throws Exception {
        CountDownLatch searching = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(searchService.hybridSearch(any(), any(), anyInt())).thenAnswer(invocation -> {
            searching.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS), "测试等待超时");
            return List.of();
        });

        qaService.answer("首问", 4, null, USER_ID);
        awaitAsyncDone(() -> searching.getCount() == 0);

        ArgumentCaptor<ConversationRound> captor = ArgumentCaptor.forClass(ConversationRound.class);
        verify(conversationService).startNewRound(captor.capture(), eq(USER_ID), eq("首问"));
        String conversationId = captor.getValue().conversationId();
        assertTrue(lockRegistry.isBusy(conversationId), "首问生成中也必须占锁");

        BizException busy = assertThrows(BizException.class,
                () -> qaService.answer("追问", 4, conversationId, USER_ID));
        assertEquals(ErrorCode.CONVERSATION_BUSY, busy.errorCode());

        resume.countDown();
    }

    @Test
    @DisplayName("他人会话：404 优先于 409，且不占用/释放他人的锁")
    void foreignConversationReturns404WithoutTouchingLock() {
        lockRegistry.tryAcquire("conv-other");
        doThrow(new BizException(ErrorCode.CONVERSATION_NOT_FOUND))
                .when(conversationService).validateOwnership("conv-other", "user-2");

        BizException exception = assertThrows(BizException.class,
                () -> qaService.answer("问题", 4, "conv-other", "user-2"));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.errorCode());
        assertTrue(lockRegistry.isBusy("conv-other"), "校验失败不得触碰他人持有的锁");
        verify(conversationService, never()).startFollowUpRound(any(), any(), any());
    }

    @Test
    @DisplayName("追问：历史按序传给生成器，检索 query 拼接最近一条历史用户提问")
    void passesHistoryAndPrependsLastUserQuestionToRetrieval() {
        when(historyAssembler.assemble("conv-1", "round-1")).thenReturn(List.of(
                new ChatTurn("USER", "首轮问题"), new ChatTurn("ASSISTANT", "首轮回答")));
        when(searchService.hybridSearch("追问", "首轮问题", 4))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));

        qaService.streamAnswer(new RecordingEmitter(), "追问", 4, USER_ID, round,
                System.currentTimeMillis());

        assertEquals(List.of(new ChatTurn("USER", "首轮问题"), new ChatTurn("ASSISTANT", "首轮回答")),
                generator.history);
        assertEquals("追问", generator.question, "传给模型的是当前问题，历史单独成列");
        verify(searchService).hybridSearch("追问", "首轮问题", 4);
    }

    @Test
    @DisplayName("首问：无历史时不拼接检索 query，生成器收到空历史")
    void firstQuestionSendsEmptyHistory() {
        when(searchService.hybridSearch("首问", null, 4))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));

        qaService.streamAnswer(new RecordingEmitter(), "首问", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(searchService).hybridSearch("首问", null, 4);
        assertEquals(List.of(), generator.history);
    }

    @Test
    @DisplayName("不同会话互不阻塞")
    void differentConversationsAreIndependent() {
        when(searchService.hybridSearch(any(), any(), anyInt())).thenReturn(List.of());

        assertDoesNotThrow(() -> qaService.answer("A", 4, "conv-a", USER_ID));
        assertDoesNotThrow(() -> qaService.answer("B", 4, "conv-b", USER_ID));
    }

    @Test
    @DisplayName("线程池拒绝：返回 500 业务异常，并落一条空正文、provider 为 null 的 FAILED 助手消息")
    void rejectedSubmissionPersistsFailedMessage() {
        QaServiceImpl svc = serviceWithRejectingExecutor();

        BizException exception = assertThrows(BizException.class,
                () -> svc.answer("问题", 4, null, USER_ID));

        ArgumentCaptor<ConversationRound> captor = ArgumentCaptor.forClass(ConversationRound.class);
        verify(conversationService).startNewRound(captor.capture(), eq(USER_ID), eq("问题"));
        String roundId = captor.getValue().roundId();

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.errorCode());
        verify(conversationService).completeRound(argThat(completion ->
                completion.roundId().equals(roundId)
                        && completion.content().isEmpty()
                        && completion.status().equals("FAILED")
                        && completion.provider() == null
                        && completion.error().equals("服务繁忙，请重试")));
    }

    @Test
    @DisplayName("追问提交失败：释放会话锁，同一会话可立即再次提问")
    void rejectedFollowUpReleasesLock() {
        QaServiceImpl svc = serviceWithRejectingExecutor();

        assertThrows(BizException.class, () -> svc.answer("追问", 4, "conv-1", USER_ID));

        assertFalse(lockRegistry.isBusy("conv-1"));
        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("FAILED")));
    }

    @Test
    @DisplayName("SSE 超时按客户端断开处理：落 STOPPED 并停止后续推送")
    void timeoutPersistsStoppedAndStopsPushing() {
        when(searchService.hybridSearch(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));
        CallbackEmitter emitter = new CallbackEmitter();
        AnswerGeneratorStrategy slow = new AnswerGeneratorStrategy() {
            @Override
            public void generate(String systemPrompt, List<ChatTurn> history, String question,
                                 List<SearchItemResponse> contextHits, Consumer<String> onDelta,
                                 com.lender.assistant.rag.strategy.AnswerCancellation cancellation) {
                onDelta.accept("前半段");
                emitter.timeoutAction.run();
                onDelta.accept("后半段不应写入");
            }

            @Override
            public String name() {
                return "Slow";
            }
        };
        QaServiceImpl svc = new QaServiceImpl(searchService, slow, resilientExecutor,
                conversationService, lockRegistry, historyAssembler);

        svc.streamAnswer(emitter, "问题", 4, USER_ID, round, System.currentTimeMillis());

        verify(conversationService).completeRound(argThat(completion ->
                completion.status().equals("STOPPED")
                        && completion.content().equals("前半段")));
    }

    /** 已 shutdown 的线程池：execute 必抛 RejectedExecutionException */
    private QaServiceImpl serviceWithRejectingExecutor() {
        ThreadPoolExecutor rejecting = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1));
        rejecting.shutdown();
        return new QaServiceImpl(searchService, generator, resilientExecutor,
                conversationService, lockRegistry, historyAssembler, rejecting);
    }
}
