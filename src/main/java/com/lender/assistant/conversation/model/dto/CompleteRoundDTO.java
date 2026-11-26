package com.lender.assistant.conversation.model.dto;

import com.lender.assistant.conversation.model.entity.MessageEntity;

/**
 * 完整轮：roundId + 双 DONE 且非 rule 回复的 USER / ASSISTANT 消息行（顺序 = 消息落库顺序）。
 *
 * 历史回放装配与滚动摘要合并（PRD §4）共用同一完整轮判定的产物（§3.6：同一算法只允许一处实现）。
 */
public record CompleteRoundDTO(String roundId, MessageEntity user, MessageEntity assistant) {
}
