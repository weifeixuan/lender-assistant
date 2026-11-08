package com.lender.assistant.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.rag.model.dto.QuestionRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上一轮路由解析：只看最近一条助手消息。
 *
 * 规则（ADR-0010）：只取最近一条状态 DONE 的助手消息的 route；
 * 最近一条是 FAILED / STOPPED 时视为无 previousRoute，不向前回溯——
 * 避免把失败轮的分类当成"上一轮意图"，导致追问保护误判。
 */
@Component
public class PreviousRouteResolver {

    private static final Logger log = LoggerFactory.getLogger(PreviousRouteResolver.class);

    private final ObjectMapper objectMapper;

    public PreviousRouteResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @param rows 会话内按 id 升序的消息；升序遍历取最后一个助手消息 */
    public QuestionRoute resolve(List<MessageEntity> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            MessageEntity row = rows.get(i);
            if (MessageConstants.ROLE_ASSISTANT.equals(row.role())) {
                return row.status() != null && row.status().equals(MessageConstants.STATUS_DONE)
                        ? routeOf(row.routeJson()) : null;
            }
        }
        return null;
    }

    private QuestionRoute routeOf(String routeJson) {
        if (routeJson == null || routeJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(routeJson).get("route");
            return node == null ? null : QuestionRoute.from(node.asText());
        } catch (Exception ex) {
            log.warn("上一轮 route JSON 解析失败，按无 previousRoute 处理: routeJson={}", routeJson, ex);
            return null;
        }
    }
}
