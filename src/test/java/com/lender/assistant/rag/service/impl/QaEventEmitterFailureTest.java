package com.lender.assistant.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO;
import com.lender.assistant.conversation.model.dto.ConversationRoundDTO;
import com.lender.assistant.conversation.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * error 事件文案兜底：框架包装异常的 getMessage() 可能为 null，
 * 直接拼接会让用户看到「问答失败：null」、落库 error 字段同样失去排查价值。
 *
 * <p>error 事件与落库共用同一个 reason 字符串，因此断言落库 error 等价于断言事件文案；
 * 这里不需要 Docker，属于纯行为测试。</p>
 */
class QaEventEmitterFailureTest {

    private final ConversationService conversationService = mock(ConversationService.class);
    private final QaEventEmitter eventEmitter = new QaEventEmitter();

    @Test
    @DisplayName("getMessage() 为 null 时文案回落异常类名，不出现 null")
    void nullMessageFallsBackToClassName() {
        String error = failureOf(new IllegalStateException());

        assertEquals("问答失败：IllegalStateException", error);
        assertFalse(error.contains("null"));
    }

    @Test
    @DisplayName("自身 message 为空时取根因 message")
    void blankMessageFallsBackToRootCause() {
        String error = failureOf(new RuntimeException((String) null,
                new IllegalStateException("连接被重置")));

        assertEquals("问答失败：连接被重置", error);
    }

    /** 走真实的 failure() 收尾路径，从终态落库 DTO 取回送给 error 事件的同一份文案 */
    private String failureOf(Exception error) {
        RoundStateServiceImpl state = new RoundStateServiceImpl(
                new ConversationRoundDTO("conv-1", "round-1", "msg-user", "msg-assistant"),
                0L, "test-model", conversationService, new ObjectMapper());

        eventEmitter.failure(new SseEmitter(), "问题", error, state);

        ArgumentCaptor<AssistantMessageCompletionDTO> captor =
                ArgumentCaptor.forClass(AssistantMessageCompletionDTO.class);
        verify(conversationService).completeRound(captor.capture());
        assertEquals(MessageConstants.STATUS_FAILED, captor.getValue().status());
        return captor.getValue().error();
    }
}
