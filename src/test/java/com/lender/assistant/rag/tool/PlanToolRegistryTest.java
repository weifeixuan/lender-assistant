package com.lender.assistant.rag.tool;

import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.tool.impl.KbSearchPlanTool;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanToolRegistryTest {

    private static final ToolContext CONTEXT = new ToolContext(4);
    private final PlanToolRegistry registry = new PlanToolRegistry(List.of(
            new KbSearchPlanTool(mock(SearchService.class))));

    @Test
    @DisplayName("按名字命中工具，未注册的名字返回空")
    void resolvesByName() {
        Optional<AgentTool> tool = registry.get("kb.search");

        assertTrue(tool.isPresent());
        assertEquals("kb.search", tool.get().name());
        assertFalse(registry.get("customer.profile").isPresent());
    }
}

class KbSearchPlanToolTest {

    private static final ToolContext CONTEXT = new ToolContext(4);

    private SearchService searchService;
    private KbSearchPlanTool tool;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        tool = new KbSearchPlanTool(searchService);
    }

    @Test
    @DisplayName("命中时返回正文摘要与 chunkId 列表，执行期 previousQuestion 传 null")
    void returnsHitsAndChunkIds() {
        when(searchService.hybridSearch(eq("华信 币种"), eq(null), eq(4)))
                .thenReturn(List.of(hit(1L, "华信接口文档", "支持美元与人民币"),
                        hit(2L, "华信接口文档", "单笔限额说明")));

        ToolResult result = tool.call(Map.of("query", "华信 币种"), CONTEXT);

        assertTrue(result.ok());
        assertEquals(List.of(1L, 2L), result.chunkIds());
        assertTrue(result.content().contains("支持美元与人民币"));
        assertFalse(result.content().contains("【来源"), "步骤内不编号：编号由编排层按并集统一分配");
    }

    @Test
    @DisplayName("显式 topK 覆盖默认值")
    void honorsExplicitTopK() {
        when(searchService.hybridSearch(any(), any(), eq(10))).thenReturn(List.of());

        tool.call(Map.of("query", "币种", "topK", 10), CONTEXT);

        verify(searchService).hybridSearch("币种", null, 10);
    }

    @Test
    @DisplayName("query 缺失、空串或超 200 字都判 ARGS_INVALID，不触发检索")
    void rejectsInvalidQuery() {
        assertEquals("ARGS_INVALID", tool.call(Map.of(), CONTEXT).errorCode());
        assertEquals("ARGS_INVALID", tool.call(Map.of("query", " "), CONTEXT).errorCode());
        assertEquals("ARGS_INVALID",
                tool.call(Map.of("query", "长".repeat(201)), CONTEXT).errorCode());
        verify(searchService, org.mockito.Mockito.never()).hybridSearch(any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("topK 越界判 ARGS_INVALID")
    void rejectsOutOfRangeTopK() {
        assertEquals("ARGS_INVALID", tool.call(Map.of("query", "币种", "topK", 0), CONTEXT).errorCode());
        assertEquals("ARGS_INVALID", tool.call(Map.of("query", "币种", "topK", 21), CONTEXT).errorCode());
    }

    @Test
    @DisplayName("零命中仍算调用成功，正文标注资料中未提及")
    void zeroHitIsSuccessfulButEmpty() {
        when(searchService.hybridSearch(any(), any(), any(Integer.class))).thenReturn(List.of());

        ToolResult result = tool.call(Map.of("query", "不存在的主题"), CONTEXT);

        assertTrue(result.ok());
        assertTrue(result.chunkIds().isEmpty());
        assertTrue(result.content().contains("资料中未提及"));
    }

    private static SearchItemResponse hit(long chunkId, String docTitle, String content) {
        return new SearchItemResponse(chunkId, "doc-1", docTitle, "1 接口", content,
                null, null, false, null);
    }
}
