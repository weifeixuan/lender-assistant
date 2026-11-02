package com.lender.assistant.conversation.service.impl;

import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.model.ConversationRow;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话查询实现：只做行到响应的映射，归属过滤与排序交给 SQL（带索引）。
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;

    public ConversationServiceImpl(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    public List<ConversationSummaryResponse> listConversations(String userId) {
        return conversationMapper.listActiveByUser(userId).stream()
                .map(ConversationServiceImpl::toResponse)
                .toList();
    }

    private static ConversationSummaryResponse toResponse(ConversationRow row) {
        return new ConversationSummaryResponse(row.conversationId(), row.title(),
                row.createTime(), row.updateTime());
    }
}
