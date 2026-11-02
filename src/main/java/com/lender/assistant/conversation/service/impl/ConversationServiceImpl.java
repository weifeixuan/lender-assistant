package com.lender.assistant.conversation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.ConversationRow;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.conversation.service.ConversationService;
import com.lender.assistant.conversation.vo.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.vo.response.MessageResponse;
import com.lender.assistant.rag.vo.response.QaSourceResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话查询实现：归属过滤与排序交给 SQL（带索引），本层只做行到响应的映射。
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final TypeReference<List<QaSourceResponse>> SOURCES_TYPE =
            new TypeReference<>() {
            };

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   MessageMapper messageMapper,
                                   ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ConversationSummaryResponse> listConversations(String userId) {
        return conversationMapper.listActiveByUser(userId).stream()
                .map(ConversationServiceImpl::toSummary)
                .toList();
    }

    @Override
    public ConversationMessagesResponse getMessages(String conversationId, String userId) {
        ConversationRow conversation =
                conversationMapper.findActiveByConversationIdAndUser(conversationId, userId);
        if (conversation == null) {
            throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        List<MessageResponse> messages = messageMapper.listByConversation(conversationId).stream()
                .map(this::toMessage)
                .toList();
        return new ConversationMessagesResponse(conversationId, messages);
    }

    private MessageResponse toMessage(MessageRow row) {
        return new MessageResponse(row.messageId(), row.roundId(), row.role(), row.content(),
                row.status(), parseSources(row.sourcesJson()), row.provider(),
                row.elapsedMs(), row.errorMsg(), row.createTime());
    }

    /** USER 消息的 sourcesJson 为 null，对外同样为 null；助手未召回时是空数组 */
    private List<QaSourceResponse> parseSources(String sourcesJson) {
        if (sourcesJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(sourcesJson, SOURCES_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息 sources JSON 无法解析", e);
        }
    }

    private static ConversationSummaryResponse toSummary(ConversationRow row) {
        return new ConversationSummaryResponse(row.conversationId(), row.title(),
                row.createTime(), row.updateTime());
    }
}
