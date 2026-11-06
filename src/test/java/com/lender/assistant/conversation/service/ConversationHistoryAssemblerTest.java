package com.lender.assistant.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.MessageRow;
import com.lender.assistant.rag.model.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationHistoryAssemblerTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String CURRENT_ROUND = "round-current";

    private MessageMapper messageMapper;
    private ConversationHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        assembler = new ConversationHistoryAssembler(messageMapper, new ObjectMapper(), 6, 2000);
    }

    @Test
    @DisplayName("排除当前轮，完整轮次按 USER→ASSISTANT 顺序回放")
    void keepsCompleteRoundsInOrder() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "问题一", "DONE"),
                row("r1", "ASSISTANT", "回答一", "DONE"),
                row(CURRENT_ROUND, "USER", "当前问题", "DONE"),
                row(CURRENT_ROUND, "ASSISTANT", "当前回答", "DONE")));

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals(List.of(new ChatTurn("USER", "问题一"), new ChatTurn("ASSISTANT", "回答一")),
                history);
    }

    @Test
    @DisplayName("缺助手消息或状态非 DONE 的轮次整轮丢弃")
    void dropsIncompleteOrFailedRounds() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "问题一", "DONE"),
                row("r1", "ASSISTANT", "半截回答", "STOPPED"),
                row("r2", "USER", "问题二", "DONE")));

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals(List.of(), history);
    }

    @Test
    @DisplayName("最多保留最近 6 轮")
    void keepsOnlyLastSixRounds() {
        List<MessageRow> rows = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            rows.add(row("r" + i, "USER", "问题" + i, "DONE"));
            rows.add(row("r" + i, "ASSISTANT", "回答" + i, "DONE"));
        }
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(rows);

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals(12, history.size());
        assertEquals("问题3", history.get(0).content());
        assertEquals("回答8", history.get(11).content());
    }

    @Test
    @DisplayName("总字符超限时从最旧一轮整轮丢弃")
    void trimsOldestRoundWhenOverCharLimit() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "a".repeat(50), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(50), "DONE"),
                row("r2", "USER", "c".repeat(50), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(50), "DONE"),
                row("r3", "USER", "e".repeat(50), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(50), "DONE")));

        ConversationHistoryAssembler limited =
                new ConversationHistoryAssembler(messageMapper, new ObjectMapper(), 6, 200);
        List<ChatTurn> history = limited.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals(4, history.size(), "丢弃最旧一轮后剩两轮");
        assertEquals("c".repeat(50), history.get(0).content());
        assertEquals("f".repeat(50), history.get(3).content());
    }

    @Test
    @DisplayName("最近一条历史用户正文用于检索 query")
    void lastUserContentReturnsMostRecentUser() {
        List<ChatTurn> history = List.of(
                new ChatTurn("USER", "问题一"), new ChatTurn("ASSISTANT", "回答一"),
                new ChatTurn("USER", "问题二"), new ChatTurn("ASSISTANT", "回答二"));

        assertEquals("问题二", ConversationHistoryAssembler.lastUserContent(history));
        assertEquals(null, ConversationHistoryAssembler.lastUserContent(List.of()));
    }

    @Test
    @DisplayName("provider=rule 的固定提示轮整轮跳过回放")
    void skipsRuleReplies() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                new MessageRow("msg-1", CONVERSATION_ID, "r1", "USER", "你好", "DONE",
                        null, null, 0, null, "2026-11-01 10:00:00", null, null),
                new MessageRow("msg-2", CONVERSATION_ID, "r1", "ASSISTANT", "固定提示", "DONE",
                        "[]", "rule", 0, null, "2026-11-01 10:00:01", null, null),
                new MessageRow("msg-3", CONVERSATION_ID, "r2", "USER", "单笔限额", "DONE",
                        null, null, 0, null, "2026-11-01 10:00:02", null, null),
                new MessageRow("msg-4", CONVERSATION_ID, "r2", "ASSISTANT", "回答", "DONE",
                        "[]", "mock", 0, null, "2026-11-01 10:00:03", null, null)));

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals(List.of(new ChatTurn("USER", "单笔限额"), new ChatTurn("ASSISTANT", "回答")),
                history);
        assertEquals("单笔限额", ConversationHistoryAssembler.lastUserContent(history));
    }

    @Test
    @DisplayName("PLAN 轮历史含业务工具产物时，助手正文追加来源标注且不改 USER 轮")
    void annotatesToolBackedAssistantTurn() {
        String content = "客户 C10001：授信 500 万元；已用 320 万元；可用 180 万元";
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "华信币种和 C10001 额度", "DONE"),
                assistantRow("r1", content, "{\"route\":\"PLAN\"}", """
                        {"degraded":false,"steps":[
                          {"stepId":"s1","order":1,"tool":"kb.search","status":"DONE"},
                          {"stepId":"s2","order":2,"tool":"customer.profile","status":"DONE"}]}
                        """)));

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals("华信币种和 C10001 额度", history.get(0).content(), "USER 轮不得被改写");
        assertEquals(content + "\n[上一轮回答包含业务工具数据：customer.profile，非知识库文档]",
                history.get(1).content());
    }

    @Test
    @DisplayName("PLAN 轮只有 kb.search 步骤时不加标注")
    void kbOnlyPlanIsNotAnnotated() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "华信单笔限额", "DONE"),
                assistantRow("r1", "单笔限额 500 万元", "{\"route\":\"PLAN\"}", """
                        {"degraded":false,"steps":[
                          {"stepId":"s1","order":1,"tool":"kb.search","status":"DONE"},
                          {"stepId":"s2","order":2,"tool":"kb.search","status":"DONE"}]}
                        """)));

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals("单笔限额 500 万元", history.get(1).content());
    }

    @Test
    @DisplayName("计划已弃用（degraded）时没有工具产物，不加标注")
    void degradedPlanIsNotAnnotated() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "华信单笔限额", "DONE"),
                assistantRow("r1", "单笔限额 500 万元", "{\"route\":\"PLAN\"}", """
                        {"degraded":true,"degradedReason":"计划校验失败","steps":[]}
                        """)));

        List<ChatTurn> history = assembler.assemble(CONVERSATION_ID, CURRENT_ROUND);

        assertEquals("单笔限额 500 万元", history.get(1).content());
    }

    private static MessageRow assistantRow(String roundId, String content,
                                           String routeJson, String planJson) {
        return new MessageRow("msg-" + roundId + "-ASSISTANT", CONVERSATION_ID, roundId,
                "ASSISTANT", content, "DONE", "[]", "deepseek-chat", 0, null,
                "2026-11-01 10:00:01", routeJson, planJson);
    }

    private static MessageRow row(String roundId, String role, String content, String status) {
        return new MessageRow("msg-" + roundId + "-" + role, CONVERSATION_ID, roundId, role,
                content, status, null, null, null, null, "2026-11-01 10:00:00", null, null);
    }
}
