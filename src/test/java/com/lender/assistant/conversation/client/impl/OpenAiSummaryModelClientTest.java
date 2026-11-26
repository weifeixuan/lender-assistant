package com.lender.assistant.conversation.client.impl;

import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
import com.lender.assistant.resilience.model.entity.LlmCallLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAiSummaryModelClient 测试：桩 ChatModel 验证「返回完整正文」与「失败上抛」。
 *
 * 协作对象用真实实例（§9）：真实 ResilientLlmExecutor + 真实 LlmCallLogger，只 mock
 * 数据访问 LlmCallLogMapper——顺带证明留痕真的发生了（scene=summary、失败记 FAIL）。
 */
class OpenAiSummaryModelClientTest {

    private static final String SUMMARY_TEXT =
            "用户在会话早期确认关注华信租赁的小额产品线，询问额度上限与所需材料，"
                    + "并约定后续提供客户号以便核对准入条件。";

    private LlmCallLogMapper mapper;
    private ChatModel chatModel;
    private LlmCallLogger llmCallLogger;

    @BeforeEach
    void setUp() {
        mapper = mock(LlmCallLogMapper.class);
        chatModel = mock(ChatModel.class);
        llmCallLogger = new LlmCallLogger(mapper);
    }

    private OpenAiSummaryModelClient newClient() {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        return new OpenAiSummaryModelClient(builder,
                new ResilientLlmExecutor(llmCallLogger, 3, 10, 2, false), llmCallLogger, "stub-model");
    }

    @Test
    @DisplayName("模型正常返回：透传完整摘要正文，client 不做截断")
    void returnsCompleteText() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(SUMMARY_TEXT)))));

        String summary = newClient().complete("合并成滚动摘要的 prompt");

        assertEquals(SUMMARY_TEXT, summary, "client 必须返回模型完整正文，硬截断归调用方");
    }

    @Test
    @DisplayName("模型调用失败：异常上抛（静默降级归调用方），且留痕记 scene=summary 的 FAIL 行")
    void failurePropagatesAndLogsFail() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型超时"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> newClient().complete("合并成滚动摘要的 prompt"),
                "失败必须原样上抛，静默降级由调用方处理");
        assertEquals("模型超时", thrown.getMessage());

        org.mockito.ArgumentCaptor<LlmCallLogEntity> captor =
                org.mockito.ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("summary", captor.getValue().scene(), "留痕场景必须是 summary");
        assertEquals("FAIL", captor.getValue().status(), "失败调用必须留 FAIL 痕");
        assertTrue(captor.getValue().errorMsg().contains("模型超时"), "失败原因必须可读");
    }
}
