package com.lender.assistant.conversation.mapper;

import com.lender.assistant.conversation.model.dto.MessageInsertDTO;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.testutil.PostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * message 的真实 SQL 验证（Testcontainers + pgvector 镜像）。
 *
 * <p>覆盖三处纯单测看不出来的数据库语义：限界子查询的「取最近 N 条 + 升序返回」、
 * JSONB 的 NULL 与 {@code '[]'} 不可混淆、{@code CAST(... AS jsonb)} 快照回填。</p>
 */
@Tag("docker")
@Transactional
class MessageMapperIntegrationTest extends PostgresContainerSupport {

    private static final String CONVERSATION = "it-conv";

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Test
    void listRecentKeepsLatestWindowAndAscendingOrder() {
        conversationMapper.insert(CONVERSATION, "it-user", "限界查询");
        for (int i = 1; i <= 5; i++) {
            messageMapper.insert(userMessage("round-" + i, "m" + i));
        }

        List<MessageEntity> recent = messageMapper.listRecentByConversation(CONVERSATION, 3);

        // 内层 id DESC 截取最近 3 条，外层再转升序 —— 顺序写反时这里立刻暴露
        assertEquals(List.of("m3", "m4", "m5"),
                recent.stream().map(MessageEntity::content).toList());
    }

    @Test
    void jsonbRoundTripKeepsNullAndEmptyArrayDistinct() {
        conversationMapper.insert(CONVERSATION, "it-user", "JSONB");
        messageMapper.insert(userMessage("round-u", "问题"));
        messageMapper.insert(new MessageInsertDTO(CONVERSATION, "round-u", "msg-a", "ASSISTANT",
                "回答", "DONE", "[]", "test-model", 12, null));

        List<MessageEntity> messages = messageMapper.listByConversation(CONVERSATION);

        assertEquals(2, messages.size());
        assertNull(messages.get(0).sourcesJson(), "用户消息未写 sources，必须是 NULL 而不是 '[]'");
        assertEquals("[]", messages.get(1).sourcesJson());
        assertEquals(12, messages.get(1).elapsedMs());
    }

    @Test
    void updateRouteAndPlanPersistsJsonbSnapshots() {
        conversationMapper.insert(CONVERSATION, "it-user", "快照");
        messageMapper.insert(new MessageInsertDTO(CONVERSATION, "round-1", "msg-a", "ASSISTANT",
                "回答", "DONE", "[]", "test-model", 5, null));

        int updated = messageMapper.updateRouteAndPlan(CONVERSATION, "round-1",
                "{\"route\":\"KNOWLEDGE\",\"confidence\":0.9}", "{\"goal\":\"对比\"}");

        assertEquals(1, updated);
        MessageEntity row = messageMapper.listByConversation(CONVERSATION).get(0);
        assertTrue(row.routeJson().contains("KNOWLEDGE"));
        assertTrue(row.executionPlanJson().contains("对比"));
    }

    private static MessageInsertDTO userMessage(String roundId, String content) {
        return new MessageInsertDTO(CONVERSATION, roundId, "msg-" + roundId, "USER",
                content, "DONE", null, null, null, null);
    }
}
