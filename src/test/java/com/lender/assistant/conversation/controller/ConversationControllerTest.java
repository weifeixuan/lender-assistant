package com.lender.assistant.conversation.controller;

import com.lender.assistant.account.model.dto.AuthenticatedAccountDTO;
import com.lender.assistant.account.service.AuthService;
import com.lender.assistant.common.constant.AuthConstants;
import com.lender.assistant.account.enums.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.web.ApiResponseBodyAdvice;
import com.lender.assistant.common.web.AuthInterceptor;
import com.lender.assistant.common.web.GlobalExceptionHandler;
import com.lender.assistant.conversation.model.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.model.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.model.response.DeleteConversationResponse;
import com.lender.assistant.conversation.model.response.MessageResponse;
import com.lender.assistant.conversation.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerTest {

    private static final String TOKEN = "test-token";
    private static final String USER_ID = "dev-user-001";

    private ConversationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ConversationService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticate(TOKEN)).thenReturn(new AuthenticatedAccountDTO(USER_ID,
                "admin", "管理员", "hash", List.of(),
                List.of(PermissionCode.KNOWLEDGE_CHAT_USE.code())));
        mockMvc = MockMvcBuilders.standaloneSetup(new ConversationController(service))
                .setControllerAdvice(new ApiResponseBodyAdvice(), new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(authService))
                .build();
    }

    @Test
    @DisplayName("GET /api/conversations 返回当前账户的会话并统一包装")
    void listsConversations() throws Exception {
        when(service.listConversations(USER_ID)).thenReturn(List.of(
                new ConversationSummaryResponse("conv-1", "首套贷款成数上限",
                        "2026-11-02 20:00:00", "2026-11-02 20:05:00")));

        mockMvc.perform(get("/api/conversations")
                        .header(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.data[0].conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data[0].title").value("首套贷款成数上限"));
    }

    @Test
    @DisplayName("没有会话时返回 200 + 空数组")
    void returnsEmptyArray() throws Exception {
        when(service.listConversations(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/conversations")
                        .header(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("旧身份请求头不再被接受：无令牌返回 401")
    void legacyUserHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/conversations").header("X-User-Id", USER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("GET /api/conversations/{id}/messages 返回扁平历史消息")
    void returnsMessages() throws Exception {
        when(service.getMessages("conv-1", USER_ID)).thenReturn(
                new ConversationMessagesResponse("conv-1", List.of(
                        new MessageResponse("msg-1", "round-1", "USER", "问题", "DONE",
                                null, null, null, null, "2026-11-02 20:00:00"))));

        mockMvc.perform(get("/api/conversations/conv-1/messages")
                        .header(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN))
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
        when(service.getMessages("conv-x", USER_ID))
                .thenThrow(new BizException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(get("/api/conversations/conv-x/messages")
                        .header(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("DELETE /api/conversations/{id} 返回删除成功")
    void deletesConversation() throws Exception {
        when(service.deleteConversation("conv-1", USER_ID))
                .thenReturn(new DeleteConversationResponse("conv-1", true));

        mockMvc.perform(delete("/api/conversations/conv-1")
                        .header(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    @DisplayName("删除不存在的会话返回 404")
    void deletingMissingConversationReturns404() throws Exception {
        when(service.deleteConversation("conv-x", USER_ID))
                .thenThrow(new BizException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(delete("/api/conversations/conv-x")
                        .header(AuthConstants.AUTHORIZATION_HEADER, "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
