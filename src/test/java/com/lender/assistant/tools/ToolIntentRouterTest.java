package com.lender.assistant.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolIntentRouter 测试：Mock 模式下"模型自主决定调用工具"的规则模拟。
 *
 * 诚实定位：真实模型的 function calling 决策在真实模型版（Spring AI .tools()），
 * 这里是它的 Mock 形态——和 HashEmbeddingStrategy 一样，验证链路而非评估效果。
 */
class ToolIntentRouterTest {

    private final ToolIntentRouter router = new ToolIntentRouter();
    private static final List<String> DOCS = List.of("ruijin_api_v1", "huaxin_corp_api_v1");

    @Test
    @DisplayName("问已入库文件 → listIngestedDocs")
    void routesToListDocs() {
        Optional<ToolIntentRouter.ToolIntent> intent = router.route("资料库里已入库的文件有哪些", () -> DOCS);
        assertTrue(intent.isPresent());
        assertEquals("listIngestedDocs", intent.get().toolName());
    }

    @Test
    @DisplayName("问某文档 chunk 数 → getDocChunkStats，参数是该 docId")
    void routesToDocStats() {
        Optional<ToolIntentRouter.ToolIntent> intent = router.route("ruijin_api_v1 这个文档切了多少块", () -> DOCS);
        assertTrue(intent.isPresent());
        assertEquals("getDocChunkStats", intent.get().toolName());
        assertEquals("ruijin_api_v1", intent.get().argument());
    }

    @Test
    @DisplayName("普通 RAG 问题不触发工具——检索问答走原有链路")
    void normalQuestionNoTool() {
        assertTrue(router.route("华信银行的逾期文件第3列是什么", () -> DOCS).isEmpty());
    }

    @Test
    @DisplayName("问 chunk 数但 docId 无效 → 不触发（参数拼不出来宁可不做）")
    void statsWithoutValidDocIdNoTool() {
        assertTrue(router.route("这个文档切了多少块", () -> DOCS).isEmpty());
    }

    @Test
    @DisplayName("null/空问题安全返回空")
    void nullSafe() {
        assertTrue(router.route(null, () -> DOCS).isEmpty());
        assertTrue(router.route("", () -> DOCS).isEmpty());
    }
}
