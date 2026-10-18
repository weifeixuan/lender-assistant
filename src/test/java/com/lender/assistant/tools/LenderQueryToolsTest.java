package com.lender.assistant.tools;

import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LenderQueryTools 测试：Function Calling 的两个业务查询工具。
 * 工具经 DocumentService 查真实数据，单测只 mock 掉 DocumentService。
 */
class LenderQueryToolsTest {

    private DocumentService documentService;
    private LenderQueryTools tools;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        tools = new LenderQueryTools(documentService);
    }

    @Test
    @DisplayName("查已入库文件列表：返回 docId/标题/chunk 数的格式化清单")
    void listIngestedDocsFormatsSummary() {
        when(documentService.listDocuments()).thenReturn(List.of(
                new DocSummary("ruijin_api_v1", "瑞金租赁_资金对接接口文档_v1.0", 5),
                new DocSummary("huaxin_corp_api_v1", "华信银行_对公收付接口文档_v1.0", 8)));

        String result = tools.listIngestedDocs();

        assertTrue(result.contains("2"));
        assertTrue(result.contains("ruijin_api_v1"));
        assertTrue(result.contains("瑞金租赁_资金对接接口文档_v1.0"));
        assertTrue(result.contains("5"), "必须带 chunk 数量");
    }

    @Test
    @DisplayName("空库：给出友好提示而非空串")
    void emptyLibraryGivesFriendlyMessage() {
        when(documentService.listDocuments()).thenReturn(List.of());
        assertTrue(tools.listIngestedDocs().contains("暂无"));
    }

    @Test
    @DisplayName("查某文档 chunk 数：已入库返回数量")
    void docChunkStatsKnownDoc() {
        when(documentService.countByDoc("ruijin_api_v1")).thenReturn(5);
        String result = tools.getDocChunkStats("ruijin_api_v1");
        assertTrue(result.contains("5"));
        assertTrue(result.contains("ruijin_api_v1"));
    }

    @Test
    @DisplayName("查某文档 chunk 数：未入库文档给出明确提示（宁说没有，不编数字）")
    void docChunkStatsUnknownDoc() {
        when(documentService.countByDoc("nope")).thenReturn(0);
        assertTrue(tools.getDocChunkStats("nope").contains("未入库"));
    }

    @Test
    @DisplayName("knownDocIds 供意图路由做 docId 匹配")
    void knownDocIdsExposed() {
        when(documentService.listDocuments()).thenReturn(List.of(
                new DocSummary("ruijin_api_v1", "瑞金", 5)));
        assertEquals(List.of("ruijin_api_v1"), tools.knownDocIds());
    }
}
