package com.lender.assistant.conversation.service;

import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.model.ConversationRow;
import com.lender.assistant.conversation.service.impl.ConversationServiceImpl;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final ConversationService service = new ConversationServiceImpl(mapper);

    @Test
    @DisplayName("会话行映射为对外响应，字段一一对应")
    void mapsRowsToResponses() {
        when(mapper.listActiveByUser("dev-user-001")).thenReturn(List.of(
                new ConversationRow("conv-1", "dev-user-001", "首套贷款成数上限",
                        "ACTIVE", "2026-11-02 20:00:00", "2026-11-02 20:05:00")));

        List<ConversationSummaryResponse> result = service.listConversations("dev-user-001");

        assertEquals(1, result.size());
        assertEquals("conv-1", result.get(0).conversationId());
        assertEquals("首套贷款成数上限", result.get(0).title());
        assertEquals("2026-11-02 20:00:00", result.get(0).createTime());
        assertEquals("2026-11-02 20:05:00", result.get(0).updateTime());
    }

    @Test
    @DisplayName("没有会话时返回空列表")
    void returnsEmptyList() {
        when(mapper.listActiveByUser("dev-user-001")).thenReturn(List.of());

        assertTrue(service.listConversations("dev-user-001").isEmpty());
    }

    @Test
    @DisplayName("按调用方传入的 userId 查询，不自行取上下文")
    void queriesByGivenUserId() {
        service.listConversations("dev-user-001");
        verify(mapper).listActiveByUser("dev-user-001");
    }
}
