package com.lender.assistant.conversation.service;

import com.lender.assistant.conversation.model.AssistantMessageCompletion;
import com.lender.assistant.conversation.model.ConversationRound;
import com.lender.assistant.conversation.vo.response.ConversationMessagesResponse;
import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;
import com.lender.assistant.conversation.vo.response.DeleteConversationResponse;

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

    /** 归属校验：不存在、已删除或非本人时抛 CONVERSATION_NOT_FOUND（必须在占会话锁之前调用） */
    void validateOwnership(String conversationId, String userId);

    /**
     * 首问懒创建：同一事务内创建会话与用户消息。
     * 本轮的 conversationId / roundId / 两条 messageId 均由调用方（QaService）生成，本 Service 不自行生成。
     */
    void startNewRound(ConversationRound round, String userId, String question);

    /** 既有会话追问：归属校验后追加用户消息并刷新会话更新时间；不存在/已删除/非本人抛 404 */
    void startFollowUpRound(ConversationRound round, String userId, String question);

    /**
     * 助手消息终态落库（DONE / FAILED / STOPPED）。
     * 会话在流式过程中被删除时助手消息照常落库（消息与会话无级联关系），但不刷新已删会话的 update_time。
     */
    void completeRound(AssistantMessageCompletion completion);
}
