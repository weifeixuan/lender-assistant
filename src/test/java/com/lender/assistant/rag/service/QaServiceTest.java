package com.lender.assistant.rag.service;

import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.service.impl.QaServiceImpl;
import com.lender.assistant.rag.strategy.AnswerGeneratorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    /** 桩生成器：抓取收到的 system Prompt、问题与召回结果，并回流一个固定 token */
    private RecordingGenerator generator;
    private QaServiceImpl qaService;
    private ConversationRound round;

    /** 测试专用 AnswerGeneratorStrategy：记录入参，不做任何模型调用 */
    static class RecordingGenerator implements AnswerGeneratorStrategy {
        String system;
        String question;
        List<SearchItemResponse> contextHits;
        final List<String> deltas = new java.util.ArrayList<>();

        @Override
        public void generate(String systemPrompt, String question, List<SearchItemResponse> contextHits,
                             Consumer<String> onDelta) {
            this.system = systemPrompt;
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
        generator = new RecordingGenerator();
        // 真实韧性执行器（chaos=false 直通）+ mock JDBC 的留痕器
        com.lender.assistant.resilience.ResilientLlmExecutor executor =
                new com.lender.assistant.resilience.ResilientLlmExecutor(
                        new com.lender.assistant.resilience.LlmCallLogger(
                                mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                        3, 10, 2, false);
        qaService = new QaServiceImpl(searchService, generator, executor, conversationService);
        round = new ConversationRound("conv-1", "round-1", "msg-user", "msg-assistant");
        when(conversationService.startNewRound(eq(USER_ID), any())).thenReturn(round);
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
        when(searchService.hybridSearch(any(), anyInt()))
                .thenReturn(List.of(hit(1, "华信银行_日末文件格式说明", "3 逾期文件", "逾期阶段 M1/M2/M3")));

        qaService.answer("逾期阶段怎么划分", 4, USER_ID);
        awaitAsyncDone(() -> generator.system != null);   // answer() 已异步：等生成器真正被调用

        assertNotNull(generator.system);
        assertTrue(generator.system.contains("三条铁律"));
        assertTrue(generator.system.contains("资料中未提及"), "铁律2：不足时明说，禁止编造");
        assertTrue(generator.system.contains("来源"), "铁律3：引用溯源格式要求");
    }

    @Test
    @DisplayName("问题与召回结果按原顺序结构化传给生成器（【来源N】编号由实现方按顺序拼）")
    void passesQuestionAndContextHitsInRecallOrder() {
        when(searchService.hybridSearch(any(), anyInt())).thenReturn(List.of(
                hit(1, "华信银行_日末文件格式说明", "3 逾期文件", "逾期阶段 M1 为 1-30 天"),
                hit(2, "华信银行_对公收付接口文档", "2 单笔代收接口", "单笔限额 500 万元")));

        qaService.answer("逾期阶段怎么划分", 4, USER_ID);
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
        when(searchService.hybridSearch(any(), anyInt())).thenReturn(List.of());

        qaService.answer("随便问", 4, USER_ID);
        // 等异步流程确认 hybridSearch 确实被调用过，再断言生成器全程未被调用
        awaitAsyncDone(() -> {
            try { verify(searchService).hybridSearch("随便问", 4); return true; }
            catch (AssertionError notYet) { return false; }
        });

        assertEquals(null, generator.system, "生成器不应被调用");
    }

    @Test
    @DisplayName("生成器抛异常：QaService 不外泄异常（error 事件在 emitter 内处理）")
    void generatorExceptionDoesNotPropagate() {
        when(searchService.hybridSearch(any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));
        AnswerGeneratorStrategy broken = new AnswerGeneratorStrategy() {
            @Override
            public void generate(String systemPrompt, String question, List<SearchItemResponse> contextHits,
                                 Consumer<String> onDelta) {
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
                        3, 10, 2, false), conversationService);

        assertDoesNotThrow(() -> svc.answer("问题", 4, USER_ID),
                "阶段4韧性改造的契约底线：模型故障不允许把异常抛出 answer() 之外");
        // 生成器异常发生在工作线程内：等异步流程跑到检索这一步即证明异常未外泄
        awaitAsyncDone(() -> {
            try { verify(searchService).hybridSearch(any(), anyInt()); return true; }
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

    @Test
    @DisplayName("meta 是流的第一个事件，之后才推送 sources/delta/done")
    void metaIsFirstEvent() {
        when(searchService.hybridSearch(any(), anyInt()))
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
    @DisplayName("正常结束把完整回答、来源与模型名落库")
    void persistsAssistantOnSuccess() {
        when(searchService.hybridSearch(any(), anyInt()))
                .thenReturn(List.of(hit(1, "某文档", "某节", "内容")));

        qaService.streamAnswer(new RecordingEmitter(), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(conversationService).completeRound(eq("conv-1"), eq("round-1"), eq("msg-assistant"),
                eq("测试答案片段"), argThat(sources -> sources.size() == 1),
                eq("RecordingMock"), anyLong());
    }

    @Test
    @DisplayName("检索无结果时把引导文案以空来源落库")
    void persistsGuidanceWhenNoHits() {
        when(searchService.hybridSearch(any(), anyInt())).thenReturn(List.of());

        qaService.streamAnswer(new RecordingEmitter(), "问题", 4, USER_ID, round,
                System.currentTimeMillis());

        verify(conversationService).completeRound(eq("conv-1"), eq("round-1"), eq("msg-assistant"),
                argThat(content -> content.contains("没有检索到相关内容")),
                argThat(List::isEmpty), eq("RecordingMock"), anyLong());
        assertNull(generator.system, "无召回不应调用生成器");
    }
}
