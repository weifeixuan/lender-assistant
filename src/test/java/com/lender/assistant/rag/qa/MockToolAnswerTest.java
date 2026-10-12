package com.lender.assistant.rag.qa;

import com.lender.assistant.rag.store.DocumentStore;
import com.lender.assistant.tools.LenderQueryTools;
import com.lender.assistant.tools.ToolIntentRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Mock 生成器的工具路径测试：工具意图问题 → 真实调用 LenderQueryTools →
 * 回答带【工具调用】标记与工具结果；普通问题走原有摘要行为不回归。
 */
class MockToolAnswerTest {

    private DocumentStore store;
    private MockAnswerGenerator generator;

    @BeforeEach
    void setUp() {
        store = mock(DocumentStore.class);
        when(store.listDocuments()).thenReturn(List.of(
                new DocumentStore.DocSummary("ruijin_api_v1", "瑞金租赁_资金对接接口文档_v1.0", 5)));
        when(store.countByDoc("ruijin_api_v1")).thenReturn(5);
        generator = new MockAnswerGenerator(new LenderQueryTools(store), new ToolIntentRouter());
    }

    private String userPromptWith(String question) {
        return "【召回资料】\n【来源1】某文档 / 某节\n某内容\n\n【问题】" + question;
    }

    private String generateAndCollect(String question) {
        List<String> deltas = new ArrayList<>();
        generator.generate("sys", userPromptWith(question), deltas::add);
        return String.join("", deltas);
    }

    @Test
    @DisplayName("问已入库文件：回答带【工具调用】标记 + 真实工具结果")
    void toolIntentTriggersToolCall() {
        String answer = generateAndCollect("已入库的文件有哪些");

        assertTrue(answer.contains("工具调用"), "必须显式标注走了工具调用（溯源红线）");
        assertTrue(answer.contains("listIngestedDocs"));
        assertTrue(answer.contains("ruijin_api_v1"), "回答内容必须来自真实工具结果");
        verify(store).listDocuments();
    }

    @Test
    @DisplayName("问某文档 chunk 数：工具参数正确传递")
    void docStatsToolCall() {
        String answer = generateAndCollect("ruijin_api_v1 这个文档切了多少块");

        assertTrue(answer.contains("getDocChunkStats"));
        assertTrue(answer.contains("5"));
        verify(store).countByDoc("ruijin_api_v1");
    }

    @Test
    @DisplayName("普通问题：不触发工具，保持原摘要行为（含 Mock 模式声明）")
    void normalQuestionKeepsSummaryBehavior() {
        String answer = generateAndCollect("华信银行的逾期文件第3列是什么");

        assertFalse(answer.contains("工具调用"));
        assertTrue(answer.contains("Mock 模式回答"));
        verify(store, never()).listDocuments();
        verify(store, never()).countByDoc(anyString());
    }
}
