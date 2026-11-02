package com.lender.assistant.conversation.controller;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.web.ApiResponseBodyAdvice;
import com.lender.assistant.common.web.GlobalExceptionHandler;
import com.lender.assistant.common.web.UserIdInterceptor;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.vo.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.vo.response.MessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerTest {

    private ConversationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ConversationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ConversationController(service))
                .setControllerAdvice(new ApiResponseBodyAdvice(), new GlobalExceptionHandler())
                .addInterceptors(new UserIdInterceptor())
                .build();
    }

    @Test
    @DisplayName("GET /api/conversations 返回当前用户的会话并统一包装")
    void listsConversations() throws Exception {
        when(service.listConversations("dev-user-001")).thenReturn(List.of(
                new ConversationSummaryResponse("conv-1", "首套贷款成数上限",
                        "2026-11-02 20:00:00", "2026-11-02 20:05:00")));

        mockMvc.perform(get("/api/conversations").header("X-User-Id", "dev-user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.data[0].conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data[0].title").value("首套贷款成数上限"));
    }

    @Test
    @DisplayName("没有会话时返回 200 + 空数组")
    void returnsEmptyArray() throws Exception {
        when(service.listConversations("dev-user-001")).thenReturn(List.of());

        mockMvc.perform(get("/api/conversations").header("X-User-Id", "dev-user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("GET /api/conversations/{id}/messages 返回扁平历史消息")
    void returnsMessages() throws Exception {
        when(service.getMessages("conv-1", "dev-user-001")).thenReturn(
                new ConversationMessagesResponse("conv-1", List.of(
                        new MessageResponse("msg-1", "round-1", "USER", "问题", "DONE",
                                null, null, null, null, "2026-11-02 20:00:00"))));

        mockMvc.perform(get("/api/conversations/conv-1/messages")
                        .header("X-User-Id", "dev-user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data.messages[0].messageId").value("msg-1"))
                .andExpect(jsonPath("$.data.messages[0].roundId").value("round-1"))
                .andExpect(jsonPath("$.data.messages[0].sources").value(nullValue()));
    }

    @Test
    @DisplayName("会话不存在时返回 404 CONVERSATION_NOT_FOUND")
    void missingConversationReturns404() throws Exception {
        when(service.getMessages("conv-x", "dev-user-001"))
                .thenThrow(new BizException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(get("/api/conversations/conv-x/messages")
                        .header("X-User-Id", "dev-user-001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
