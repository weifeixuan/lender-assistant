package com.lender.assistant.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.model.dto.AssistantMessageCompletionDTO;
import com.lender.assistant.conversation.model.dto.ConversationRoundDTO;
import com.lender.assistant.conversation.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RoundStateServiceImplTest {

    private final ConversationService conversationService = mock(ConversationService.class);

    @Test
    @DisplayName("终态落库取 provider 最新值，且只落一次")
    void persistsLatestProviderOnce() {
        RoundStateServiceImpl state = new RoundStateServiceImpl(
                new ConversationRoundDTO("conv-1", "round-1", "msg-user", "msg-assistant"),
                0L, "rule", conversationService, new ObjectMapper());

        state.provider("workbuddy");
        state.done();
        state.failed("迟到错误不应覆盖终态");

        ArgumentCaptor<AssistantMessageCompletionDTO> captor =
                ArgumentCaptor.forClass(AssistantMessageCompletionDTO.class);
        verify(conversationService, times(1)).completeRound(captor.capture());
        assertEquals("workbuddy", captor.getValue().provider(), "落库必须是终态前最新的 provider");
        assertEquals(MessageConstants.STATUS_DONE, captor.getValue().status(), "CAS 闸门保证只落一次");
    }
}
