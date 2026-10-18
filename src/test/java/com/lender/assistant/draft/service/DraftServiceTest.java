package com.lender.assistant.draft.service;

import com.lender.assistant.draft.service.impl.DraftServiceImpl;
import com.lender.assistant.extract.FieldMappingStore;
import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DraftService 编排测试：加载映射表 → 经留痕调生成器 → 复核声明。
 * 用真实 LlmCallLogger（supplier 真执行）+ mock 掉 JDBC，与 ExtractServiceTest 同一手法。
 */
class DraftServiceTest {

    private DraftGenerator generator;
    private FieldMappingStore store;
    private LlmCallLogger logger;
    private DraftService service;

    @BeforeEach
    void setUp() {
        generator = mock(DraftGenerator.class);
        store = mock(FieldMappingStore.class);
        // 真实留痕器（supplier 真执行、异常真上抛）+ mock 掉 JDBC；
        // 韧性执行器也用真实实例（chaos=false），验证 scene=draft 确实经过它
        logger = spy(new LlmCallLogger(mock(LlmCallLogMapper.class)));
        ResilientLlmExecutor executor = new ResilientLlmExecutor(logger, 3, 30, 2, false);
        service = new DraftServiceImpl(generator, store, executor);

        when(generator.name()).thenReturn("Mock");
        when(generator.generateDraft(anyString(), ArgumentMatchers.anyList()))
                .thenReturn("public class Draft { }");
        when(store.listFieldMappings("doc1")).thenReturn(List.of(
                new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                        "https://fake-x.example.com", "网关地址 https://fake-x.example.com", 0.9)));
    }

    @Test
    @DisplayName("映射表为空 → 引导先做字段抽取，而不是生成空壳草稿")
    void noMappingsGuidesToExtractFirst() {
        when(store.listFieldMappings("doc_none")).thenReturn(List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.generate("doc_none"));
        assertTrue(e.getMessage().contains("字段抽取"), "提示信息要指路: " + e.getMessage());
        verify(generator, never()).generateDraft(anyString(), ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("生成调用必须经留痕器包装，scene=draft（抽取可重试，草稿同样非流式可重试）")
    void draftGoesThroughLogger() {
        service.generate("doc1");

        verify(logger).record(eq("draft"), eq("Mock"), any(Supplier.class));
        verify(generator).generateDraft(eq("doc1"), anyList());
    }

    @Test
    @DisplayName("返回体必须带人工复核声明——草稿严禁直接上生产")
    void resultCarriesReviewNotice() {
        DraftService.DraftResult result = service.generate("doc1");

        assertTrue(result.reviewNotice().contains("人工复核"));
        assertEquals("doc1", result.docId());
        assertEquals("Mock", result.provider());
        assertEquals(1, result.mappingsUsed());
    }
}
