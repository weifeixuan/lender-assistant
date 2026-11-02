package com.lender.assistant.conversation.controller;

import com.lender.assistant.common.web.ApiResponseBodyAdvice;
import com.lender.assistant.common.web.GlobalExceptionHandler;
import com.lender.assistant.common.web.UserIdInterceptor;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
    @DisplayName("缺失 X-User-Id 返回 400 PARAM_INVALID，普通 JSON 而非 SSE")
    void missingUserIdReturns400() throws Exception {
        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
