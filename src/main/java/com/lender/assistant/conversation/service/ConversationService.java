package com.lender.assistant.conversation.service;

import com.lender.assistant.conversation.vo.response.ConversationSummaryResponse;

import java.util.List;

/**
 * 会话查询入口。事务边界在实现类；controller 只传 userId、组装响应。
 */
public interface ConversationService {

    /** 当前用户未删除的会话，按最近更新排序 */
    List<ConversationSummaryResponse> listConversations(String userId);
}
