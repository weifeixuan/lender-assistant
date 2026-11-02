package com.lender.assistant.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.ConversationRow;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.conversation.service.impl.ConversationServiceImpl;
import com.lender.assistant.conversation.vo.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.vo.response.MessageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private static ConversationRow activeConversation() {
        return new ConversationRow(CONVERSATION_ID, USER_ID, "标题", "ACTIVE",
                "2026-11-02 20:00:00", "2026-11-02 20:05:00");
    }
}
