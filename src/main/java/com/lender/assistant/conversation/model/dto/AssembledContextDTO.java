package com.lender.assistant.conversation.model.dto;

import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.rag.model.dto.ChatTurnDTO;

import java.util.List;

/**
 * 一次历史装配的全部产物：窗口行 + 回放历史 + 可注入摘要。
 *
 * @param rows    会话内最近的原始消息行（id 升序），上一轮路由解析与历史装配共用，避免重复查库
 * @param history 排除当前轮后的回放窗口（USER→ASSISTANT 交替）
 * @param summary 本轮可注入的滚动摘要正文；null 表示不可注入（无摘要 / 边界未早于窗口最旧轮等）
 */
public record AssembledContextDTO(List<MessageEntity> rows, List<ChatTurnDTO> history, String summary) {
}
