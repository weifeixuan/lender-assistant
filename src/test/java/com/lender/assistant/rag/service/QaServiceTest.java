package com.lender.assistant.rag.service;

import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.service.impl.QaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 开工前的测试基线：锁住 Prompt 两段式拼装契约。
 *
 * 测的是公共契约而非私有方法：QaService.answer 内部把 systemPrompt/userPrompt
 * 交给 AnswerGenerator——用桩生成器把两个 Prompt 抓出来断言。
 * Prompt 三铁律和【来源N】格式是模型行为的根基，改动前必须先有这道护栏。
 *
 * 异步契约（2026-10-17 SSE 真流式改造后）：answer() 立即 return emitter，检索/生成/send
 * 在独立工作线程执行——故所有断言都必须用 awaitAsyncDone 等流程真正跑完，不能按"同步返回即已推完"写。
 */
class QaServiceTest {

    private SearchService searchService;
    /** 桩生成器：抓取收到的 system/user Prompt，并回流一个固定 token */
    private RecordingGenerator generator;
    private QaService qaService;

    /** 测试专用 AnswerGenerator：记录入参，不做任何模型调用 */
    static class RecordingGenerator implements AnswerGenerator {
        String system;
        String user;
        final List<String> deltas = new java.util.ArrayList<>();

        @Override
        public void generate(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
            this.system = systemPrompt;
            this.user = userPrompt;
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
        generator = new RecordingGenerator();
        // 真实韧性执行器（chaos=false 直通）+ mock JDBC 的留痕器
        com.lender.assistant.resilience.ResilientLlmExecutor executor =
                new com.lender.assistant.resilience.ResilientLlmExecutor(
                        new com.lender.assistant.resilience.LlmCallLogger(
                                mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class)),
                        3, 10, 2, false);
        qaService = new QaServiceImpl(searchService, generator, executor);
    }

    private Map<String, Object> hit(long chunkId, String title, String section, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkId", chunkId);
        m.put("docId", "doc1");
        m.put("docTitle", title);
        m.put("sectionPath", section);
        m.put("content", content);
        return m;
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

        qaService.answer("逾期阶段怎么划分", 4);
        awaitAsyncDone(() -> generator.system != null);   // answer() 已异步：等生成器真正被调用

        assertNotNull(generator.system);
        assertTrue(generator.system.contains("三条铁律"));
        assertTrue(generator.system.contains("资料中未提及"), "铁律2：不足时明说，禁止编造");
        assertTrue(generator.system.contains("来源"), "铁律3：引用溯源格式要求");
    }

    @Test
    @DisplayName("userPrompt 按【来源N】拼召回资料，末尾附问题")
    void userPromptAssemblesSourceBlocks() {
        when(searchService.hybridSearch(any(), anyInt())).thenReturn(List.of(
                hit(1, "华信银行_日末文件格式说明", "3 逾期文件", "逾期阶段 M1 为 1-30 天"),
                hit(2, "华信银行_对公收付接口文档", "2 单笔代收接口", "单笔限额 500 万元")));

        qaService.answer("逾期阶段怎么划分", 4);
        awaitAsyncDone(() -> generator.user != null);   // answer() 已异步：等生成器真正被调用

        assertNotNull(generator.user);
        assertTrue(generator.user.startsWith("【召回资料】"));
        assertTrue(generator.user.contains("【来源1】华信银行_日末文件格式说明 / 3 逾期文件"));
        assertTrue(generator.user.contains("【来源2】华信银行_对公收付接口文档 / 2 单笔代收接口"));
        assertTrue(generator.user.contains("逾期阶段 M1 为 1-30 天"));
        assertTrue(generator.user.endsWith("【问题】逾期阶段怎么划分"));
        // 顺序断言：来源块必须全部出现在问题之前
        assertTrue(generator.user.indexOf("【来源2】") < generator.user.indexOf("【问题】"));
    }

    @Test
    @DisplayName("检索无结果：不调用生成器，直接给出引导文案")
    void emptyHitsSkipsGenerator() {
        when(searchService.hybridSearch(any(), anyInt())).thenReturn(List.of());

        qaService.answer("随便问", 4);
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
        AnswerGenerator broken = new AnswerGenerator() {
            @Override
            public void generate(String s, String u, Consumer<String> onDelta) {
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
                        3, 10, 2, false));

        assertDoesNotThrow(() -> svc.answer("问题", 4),
                "阶段4韧性改造的契约底线：模型故障不允许把异常抛出 answer() 之外");
        // 生成器异常发生在工作线程内：等异步流程跑到检索这一步即证明异常未外泄
        awaitAsyncDone(() -> {
            try { verify(searchService).hybridSearch(any(), anyInt()); return true; }
            catch (AssertionError notYet) { return false; }
        });
    }
}
