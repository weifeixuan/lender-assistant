package com.lender.assistant.rag.controller;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.web.ApiResponseBodyAdvice;
import com.lender.assistant.common.web.GlobalExceptionHandler;
import com.lender.assistant.common.web.UserIdInterceptor;
import com.lender.assistant.rag.service.QaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答接口的 HTTP 层契约：400 / 404 / 409 都在返回 SseEmitter 之前同步抛出，
 * 因此必须是普通 JSON 错误体，而不是半开 SSE 流。
 */
class QaControllerTest {

    private static final String USER_ID = "dev-user-001";
    private static final String CONVERSATION_ID = "11111111-1111-1111-1111-111111111111";

    private QaService qaService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        qaService = mock(QaService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new QaController(qaService))
                .setControllerAdvice(new ApiResponseBodyAdvice(), new GlobalExceptionHandler())
                .addInterceptors(new UserIdInterceptor())
                .build();
    }

    @Test
    @DisplayName("conversationId 非 UUID → 400 PARAM_INVALID，普通 JSON")
    void invalidConversationIdReturns400() throws Exception {
        mockMvc.perform(post("/api/qa")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"question\":\"问题\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("追问的会话不可见 → 404 CONVERSATION_NOT_FOUND")
    void invisibleConversationReturns404() throws Exception {
        when(qaService.answer(eq("问题"), anyInt(), eq(CONVERSATION_ID), eq(USER_ID)))
                .thenThrow(new BizException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(post("/api/qa")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + CONVERSATION_ID + "\",\"question\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("同一会话并发提问 → 409 CONVERSATION_BUSY")
    void busyConversationReturns409() throws Exception {
        when(qaService.answer(eq("问题"), anyInt(), eq(CONVERSATION_ID), eq(USER_ID)))
                .thenThrow(new BizException(ErrorCode.CONVERSATION_BUSY));

        mockMvc.perform(post("/api/qa")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + CONVERSATION_ID + "\",\"question\":\"问题\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("合法请求把 question、topK 默认值与 conversationId 透传给服务层")
    void delegatesTrimmedQuestionAndIds() throws Exception {
        when(qaService.answer(eq("问题"), eq(4), eq(CONVERSATION_ID), eq(USER_ID)))
                .thenReturn(new SseEmitter());

        mockMvc.perform(post("/api/qa")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + CONVERSATION_ID + "\",\"question\":\"  问题  \"}"))
                .andExpect(status().isOk());

        verify(qaService).answer("问题", 4, CONVERSATION_ID, USER_ID);
    }
}
