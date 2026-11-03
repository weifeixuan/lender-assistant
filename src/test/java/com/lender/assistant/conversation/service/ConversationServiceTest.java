package com.lender.assistant.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.AssistantMessageCompletion;
import com.lender.assistant.conversation.model.ConversationRow;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.conversation.service.impl.ConversationServiceImpl;
import com.lender.assistant.conversation.vo.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.vo.response.DeleteConversationResponse;
import com.lender.assistant.conversation.vo.response.MessageResponse;
import com.lender.assistant.rag.vo.response.QaSourceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String USER_ID = "dev-user-001";

    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ConversationService service =
            new ConversationServiceImpl(conversationMapper, messageMapper, new ObjectMapper());

    @Test
    @DisplayName("会话行映射为对外响应，字段一一对应")
    void mapsRowsToResponses() {
        when(conversationMapper.listActiveByUser(USER_ID)).thenReturn(List.of(
                new ConversationRow(CONVERSATION_ID, USER_ID, "首套贷款成数上限",
                        "ACTIVE", "2026-11-02 20:00:00", "2026-11-02 20:05:00")));

        List<ConversationSummaryResponse> result = service.listConversations(USER_ID);

        assertEquals(1, result.size());
        assertEquals(CONVERSATION_ID, result.get(0).conversationId());
        assertEquals("首套贷款成数上限", result.get(0).title());
        assertEquals("2026-11-02 20:00:00", result.get(0).createTime());
        assertEquals("2026-11-02 20:05:00", result.get(0).updateTime());
    }

    @Test
    @DisplayName("没有会话时返回空列表")
    void returnsEmptyList() {
        when(conversationMapper.listActiveByUser(USER_ID)).thenReturn(List.of());

        assertTrue(service.listConversations(USER_ID).isEmpty());
    }

    @Test
    @DisplayName("按调用方传入的 userId 查询，不自行取上下文")
    void queriesByGivenUserId() {
        service.listConversations(USER_ID);
        verify(conversationMapper).listActiveByUser(USER_ID);
    }

    @Test
    @DisplayName("历史消息按 id 顺序映射，同一轮共享 roundId，sources 还原为对象")
    void mapsHistoryMessages() {
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(activeConversation());
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                new MessageRow("msg-user", CONVERSATION_ID, "round-1", "USER", "问题",
                        "DONE", null, null, null, null, "2026-11-02 20:00:00"),
                new MessageRow("msg-assistant", CONVERSATION_ID, "round-1", "ASSISTANT", "回答",
                        "DONE", "[{\"idx\":1,\"docTitle\":\"资方接口文档\",\"chunkId\":123}]",
                        "deepseek-chat", 1234, null, "2026-11-02 20:00:01")));

        ConversationMessagesResponse result = service.getMessages(CONVERSATION_ID, USER_ID);

        assertEquals(CONVERSATION_ID, result.conversationId());
        assertEquals(2, result.messages().size());
        MessageResponse user = result.messages().get(0);
        MessageResponse assistant = result.messages().get(1);
        assertEquals("round-1", user.roundId());
        assertEquals("round-1", assistant.roundId());
        assertNull(user.sources());
        assertEquals(1, assistant.sources().size());
        assertEquals("资方接口文档", assistant.sources().get(0).docTitle());
        assertEquals(1234, assistant.elapsedMillis());
        assertNull(assistant.error());
    }

    @Test
    @DisplayName("空会话返回空消息数组，不返回 404")
    void returnsEmptyHistory() {
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(activeConversation());
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of());

        ConversationMessagesResponse result = service.getMessages(CONVERSATION_ID, USER_ID);

        assertEquals(CONVERSATION_ID, result.conversationId());
        assertTrue(result.messages().isEmpty());
    }

    @Test
    @DisplayName("会话不存在、已删除或不属于当前用户时返回 404")
    void missingConversationReturns404() {
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.getMessages(CONVERSATION_ID, USER_ID));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.errorCode());
    }

    @Test
    @DisplayName("读取历史时用 conversationId + userId 做归属校验")
    void historyChecksOwnership() {
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(activeConversation());
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of());

        service.getMessages(CONVERSATION_ID, USER_ID);

        verify(conversationMapper).findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID);
    }

    @Test
    @DisplayName("首问创建会话与用户消息，四项 id 使用调用方（QaService）生成的值")
    void startsNewRound() {
        when(conversationMapper.insert(anyString(), eq(USER_ID), anyString())).thenReturn(1);
        when(messageMapper.insert(any())).thenReturn(1);

        ConversationRound round = new ConversationRound("conv-new", "round-new",
                "msg-user-new", "msg-assistant-new");
        service.startNewRound(round, USER_ID, "首套贷款成数上限是多少");

        verify(conversationMapper).insert("conv-new", USER_ID, "首套贷款成数上限是多少");

        verify(messageMapper).insert(argThat(message ->
                message.conversationId().equals("conv-new")
                        && message.roundId().equals("round-new")
                        && message.messageId().equals("msg-user-new")
                        && message.role().equals("USER")
                        && message.status().equals("DONE")
                        && message.sourcesJson() == null));
    }

    @Test
    @DisplayName("会话标题取去空白后的前 20 个 Unicode code point")
    void titleUsesTwentyCodePoints() {
        String question = "  " + "一".repeat(20) + "二三四五  ";
        when(conversationMapper.insert(anyString(), eq(USER_ID), anyString())).thenReturn(1);
        when(messageMapper.insert(any())).thenReturn(1);

        service.startNewRound(new ConversationRound("conv-new", "round-new",
                "msg-user-new", "msg-assistant-new"), USER_ID, question);

        verify(conversationMapper).insert(anyString(), eq(USER_ID), eq("一".repeat(20)));
    }

    @Test
    @DisplayName("助手正常结束落库 DONE，并刷新会话更新时间")
    void completesRound() {
        when(messageMapper.insert(any())).thenReturn(1);
        when(conversationMapper.touch(CONVERSATION_ID)).thenReturn(1);

        service.completeRound(new AssistantMessageCompletion(CONVERSATION_ID, "round-1",
                "msg-assistant", "回答正文", List.of(source()), "Mock", 1234, "DONE", null));

        ArgumentCaptor<com.lender.assistant.conversation.model.MessageInsert> insert =
                ArgumentCaptor.forClass(com.lender.assistant.conversation.model.MessageInsert.class);
        verify(messageMapper).insert(insert.capture());
        assertEquals("ASSISTANT", insert.getValue().role());
        assertEquals("DONE", insert.getValue().status());
        assertEquals("回答正文", insert.getValue().content());
        assertTrue(insert.getValue().sourcesJson().contains("\"idx\""));
        verify(conversationMapper).touch(CONVERSATION_ID);
    }

    @Test
    @DisplayName("助手未召回时 sources 落库为空数组")
    void completesRoundWithEmptySources() {
        when(messageMapper.insert(any())).thenReturn(1);
        when(conversationMapper.touch(CONVERSATION_ID)).thenReturn(1);

        service.completeRound(new AssistantMessageCompletion(CONVERSATION_ID, "round-1",
                "msg-assistant", "未检索到", List.of(), "Mock", 10, "DONE", null));

        verify(messageMapper).insert(argThat(message ->
                message.sourcesJson().equals("[]")));
    }

    @Test
    @DisplayName("FAILED 终态：保留已生成正文与 error 字段")
    void persistsFailedRound() {
        when(messageMapper.insert(any())).thenReturn(1);
        when(conversationMapper.touch(CONVERSATION_ID)).thenReturn(1);

        service.completeRound(new AssistantMessageCompletion(CONVERSATION_ID, "round-1",
                "msg-assistant", "已生成的部分", List.of(), "Mock", 5, "FAILED", "模型超时"));

        verify(messageMapper).insert(argThat(message ->
                message.status().equals("FAILED")
                        && message.content().equals("已生成的部分")
                        && message.errorMsg().equals("模型超时")));
    }

    @Test
    @DisplayName("STOPPED 终态：保留已生成正文，error 为空")
    void persistsStoppedRound() {
        when(messageMapper.insert(any())).thenReturn(1);
        when(conversationMapper.touch(CONVERSATION_ID)).thenReturn(1);

        service.completeRound(new AssistantMessageCompletion(CONVERSATION_ID, "round-1",
                "msg-assistant", "半截回答", List.of(source()), "Mock", 5, "STOPPED", null));

        verify(messageMapper).insert(argThat(message ->
                message.status().equals("STOPPED")
                        && message.content().equals("半截回答")
                        && message.errorMsg() == null));
    }

    @Test
    @DisplayName("流式过程中会话被删除：助手消息照常落库，但已删会话的时间线不被推后")
    void persistsMessageEvenWhenConversationDeletedDuringStream() {
        when(messageMapper.insert(any())).thenReturn(1);
        when(conversationMapper.touch(CONVERSATION_ID)).thenReturn(0);

        service.completeRound(new AssistantMessageCompletion(CONVERSATION_ID, "round-1",
                "msg-assistant", "回答", List.of(), "Mock", 10, "DONE", null));

        verify(messageMapper).insert(argThat(message ->
                message.conversationId().equals(CONVERSATION_ID)
                        && message.roundId().equals("round-1")
                        && message.messageId().equals("msg-assistant")
                        && message.role().equals("ASSISTANT")
                        && message.content().equals("回答")));
        verify(conversationMapper).touch(CONVERSATION_ID);
    }

    @Test
    @DisplayName("追问在既有会话下追加用户消息，并刷新会话更新时间")
    void startsFollowUpRound() {
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(activeConversation());
        when(messageMapper.insert(any())).thenReturn(1);
        when(conversationMapper.touch(CONVERSATION_ID)).thenReturn(1);

        ConversationRound round = new ConversationRound(CONVERSATION_ID, "round-2",
                "msg-user-2", "msg-assistant-2");
        service.startFollowUpRound(round, USER_ID, "那二套呢");

        verify(messageMapper).insert(argThat(message ->
                message.conversationId().equals(CONVERSATION_ID)
                        && message.roundId().equals("round-2")
                        && message.messageId().equals("msg-user-2")
                        && message.role().equals("USER")
                        && message.status().equals("DONE")));
        verify(conversationMapper).touch(CONVERSATION_ID);
    }

    @Test
    @DisplayName("追问的会话不存在、已删除或不属于当前用户时返回 404，且不写消息")
    void followUpOnMissingConversationReturns404() {
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(null);

        ConversationRound round = new ConversationRound(CONVERSATION_ID, "round-2",
                "msg-user-2", "msg-assistant-2");
        BizException exception = assertThrows(BizException.class,
                () -> service.startFollowUpRound(round, USER_ID, "问题"));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.errorCode());
        verify(messageMapper, org.mockito.Mockito.never()).insert(any());
    }

    private static QaSourceResponse source() {
        return new QaSourceResponse(1, "资方接口文档", "3 日末文件", 123L,
                "切片正文", null, null, false, null);
    }

    @Test
    @DisplayName("删除会话成功时返回 deleted=true")
    void deletesConversation() {
        when(conversationMapper.softDelete(CONVERSATION_ID, USER_ID)).thenReturn(1);

        DeleteConversationResponse result = service.deleteConversation(CONVERSATION_ID, USER_ID);

        assertEquals(CONVERSATION_ID, result.conversationId());
        assertTrue(result.deleted());
        verify(conversationMapper).softDelete(CONVERSATION_ID, USER_ID);
    }

    @Test
    @DisplayName("删除不存在的、已删除的或他人的会话返回 404")
    void deletingMissingConversationReturns404() {
        when(conversationMapper.softDelete(CONVERSATION_ID, USER_ID)).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.deleteConversation(CONVERSATION_ID, USER_ID));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.errorCode());
    }

    private static ConversationRow activeConversation() {
        return new ConversationRow(CONVERSATION_ID, USER_ID, "标题", "ACTIVE",
                "2026-11-02 20:00:00", "2026-11-02 20:05:00");
    }
}
