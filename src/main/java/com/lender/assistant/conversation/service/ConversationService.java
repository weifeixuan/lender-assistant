package com.lender.assistant.conversation.service;

import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.conversation.vo.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.vo.response.DeleteConversationResponse;
import com.lender.assistant.rag.vo.response.QaSourceResponse;

import java.util.List;

/**
 * 会话查询入口。事务边界在实现类；controller 只传 userId、组装响应。
 */
public interface ConversationService {

    /** 当前用户未删除的会话，按最近更新排序 */
    List<ConversationSummaryResponse> listConversations(String userId);

    /** 归属校验后返回扁平历史消息；会话不可见时抛 CONVERSATION_NOT_FOUND */
    ConversationMessagesResponse getMessages(String conversationId, String userId);

    /** 软删会话；不存在、已删除或非本人时抛 CONVERSATION_NOT_FOUND */
    DeleteConversationResponse deleteConversation(String conversationId, String userId);

    /** 首问懒创建：同一事务内创建会话与用户消息，返回本轮四项标识 */
    ConversationRound startNewRound(String userId, String question);

    /** 正常结束落助手消息（DONE）并刷新会话更新时间 */
    void completeRound(String conversationId, String roundId, String assistantMessageId,
                       String content, List<QaSourceResponse> sources,
                       String provider, long elapsedMillis);
}
