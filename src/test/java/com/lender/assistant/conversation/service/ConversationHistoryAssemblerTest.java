package com.lender.assistant.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.dto.AssembledContextDTO;
import com.lender.assistant.conversation.model.entity.ConversationEntity;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.rag.model.dto.ChatTurnDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationHistoryAssemblerTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String USER_ID = "user-1";
    private static final String CURRENT_ROUND = "round-current";

    private MessageMapper messageMapper;
    private ConversationMapper conversationMapper;
    private ConversationHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        // 默认无摘要会话：summary 与边界同写同空
        when(conversationMapper.findActiveByConversationIdAndUser(anyString(), anyString()))
                .thenReturn(conversation(null, null));
        assembler = new ConversationHistoryAssembler(messageMapper, conversationMapper,
                new ObjectMapper(), 6, 2000);
    }

    @Test
    @DisplayName("排除当前轮，完整轮次按 USER→ASSISTANT 顺序回放")
    void keepsCompleteRoundsInOrder() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "问题一", "DONE"),
                row("r1", "ASSISTANT", "回答一", "DONE"),
                row(CURRENT_ROUND, "USER", "当前问题", "DONE"),
                row(CURRENT_ROUND, "ASSISTANT", "当前回答", "DONE")));

        AssembledContextDTO assembled =
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND);
        verify(messageMapper).listRecentByConversation(CONVERSATION_ID, 14);

        assertEquals(List.of(new ChatTurnDTO("USER", "问题一"), new ChatTurnDTO("ASSISTANT", "回答一")),
                assembled.history());
        assertNull(assembled.summary());
    }

    @Test
    @DisplayName("缺助手消息或状态非 DONE 的轮次整轮丢弃")
    void dropsIncompleteOrFailedRounds() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "问题一", "DONE"),
                row("r1", "ASSISTANT", "半截回答", "STOPPED"),
                row("r2", "USER", "问题二", "DONE")));

        assertEquals(List.of(),
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history());
    }

    @Test
    @DisplayName("最多保留最近 6 轮")
    void keepsOnlyLastSixRounds() {
        List<MessageEntity> rows = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            rows.add(row("r" + i, "USER", "问题" + i, "DONE"));
            rows.add(row("r" + i, "ASSISTANT", "回答" + i, "DONE"));
        }
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(rows);

        List<ChatTurnDTO> history =
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history();

        assertEquals(12, history.size());
        assertEquals("问题3", history.get(0).content());
        assertEquals("回答8", history.get(11).content());
    }

    @Test
    @DisplayName("总字符超限时从最旧一轮整轮丢弃")
    void trimsOldestRoundWhenOverCharLimit() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "a".repeat(50), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(50), "DONE"),
                row("r2", "USER", "c".repeat(50), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(50), "DONE"),
                row("r3", "USER", "e".repeat(50), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(50), "DONE")));

        ConversationHistoryAssembler limited = new ConversationHistoryAssembler(
                messageMapper, conversationMapper, new ObjectMapper(), 6, 200);
        List<ChatTurnDTO> history =
                limited.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history();

        assertEquals(4, history.size(), "丢弃最旧一轮后剩两轮");
        assertEquals("c".repeat(50), history.get(0).content());
        assertEquals("f".repeat(50), history.get(3).content());
    }

    @Test
    @DisplayName("最近一条历史用户正文用于检索 query")
    void lastUserContentReturnsMostRecentUser() {
        List<ChatTurnDTO> history = List.of(
                new ChatTurnDTO("USER", "问题一"), new ChatTurnDTO("ASSISTANT", "回答一"),
                new ChatTurnDTO("USER", "问题二"), new ChatTurnDTO("ASSISTANT", "回答二"));

        assertEquals("问题二", ConversationHistoryAssembler.lastUserContent(history));
        assertEquals(null, ConversationHistoryAssembler.lastUserContent(List.of()));
    }

    @Test
    @DisplayName("provider=rule 的固定提示轮整轮跳过回放")
    void skipsRuleReplies() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                new MessageEntity("msg-1", CONVERSATION_ID, "r1", "USER", "你好", "DONE",
                        null, null, 0, null, "2026-11-01 10:00:00", null, null),
                new MessageEntity("msg-2", CONVERSATION_ID, "r1", "ASSISTANT", "固定提示", "DONE",
                        "[]", "rule", 0, null, "2026-11-01 10:00:01", null, null),
                new MessageEntity("msg-3", CONVERSATION_ID, "r2", "USER", "单笔限额", "DONE",
                        null, null, 0, null, "2026-11-01 10:00:02", null, null),
                new MessageEntity("msg-4", CONVERSATION_ID, "r2", "ASSISTANT", "回答", "DONE",
                        "[]", "mock", 0, null, "2026-11-01 10:00:03", null, null)));

        List<ChatTurnDTO> history =
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history();

        assertEquals(List.of(new ChatTurnDTO("USER", "单笔限额"), new ChatTurnDTO("ASSISTANT", "回答")),
                history);
        assertEquals("单笔限额", ConversationHistoryAssembler.lastUserContent(history));
    }

    @Test
    @DisplayName("PLAN 轮历史含业务工具产物时，助手正文追加来源标注且不改 USER 轮")
    void annotatesToolBackedAssistantTurn() {
        String content = "客户 C10001：授信 500 万元；已用 320 万元；可用 180 万元";
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "华信币种和 C10001 额度", "DONE"),
                assistantRow("r1", content, "{\"route\":\"PLAN\"}", """
                        {"degraded":false,"steps":[
                          {"stepId":"s1","order":1,"tool":"kb.search","status":"DONE"},
                          {"stepId":"s2","order":2,"tool":"customer.profile","status":"DONE"}]}
                        """)));

        List<ChatTurnDTO> history =
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history();

        assertEquals("华信币种和 C10001 额度", history.get(0).content(), "USER 轮不得被改写");
        assertEquals(content + "\n[上一轮回答包含业务工具数据：customer.profile，非知识库文档]",
                history.get(1).content());
    }

    @Test
    @DisplayName("PLAN 轮只有 kb.search 步骤时不加标注")
    void kbOnlyPlanIsNotAnnotated() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "华信单笔限额", "DONE"),
                assistantRow("r1", "单笔限额 500 万元", "{\"route\":\"PLAN\"}", """
                        {"degraded":false,"steps":[
                          {"stepId":"s1","order":1,"tool":"kb.search","status":"DONE"},
                          {"stepId":"s2","order":2,"tool":"kb.search","status":"DONE"}]}
                        """)));

        List<ChatTurnDTO> history =
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history();

        assertEquals("单笔限额 500 万元", history.get(1).content());
    }

    @Test
    @DisplayName("计划已弃用（degraded）时没有工具产物，不加标注")
    void degradedPlanIsNotAnnotated() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "华信单笔限额", "DONE"),
                assistantRow("r1", "单笔限额 500 万元", "{\"route\":\"PLAN\"}", """
                        {"degraded":true,"degradedReason":"计划校验失败","steps":[]}
                        """)));

        List<ChatTurnDTO> history =
                assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).history();

        assertEquals("单笔限额 500 万元", history.get(1).content());
    }

    @Test
    @DisplayName("摘要边界轮严格早于窗口最旧轮：装配结果携带摘要正文")
    void returnsSummaryWhenBoundaryStrictlyBeforeWindowOldest() {
        // 手算：3 个完整轮各 120 字符，maxChars=250 → 窗口从 r2 起（r2+r3 共 240 ≤ 250），r1 被裁；
        // 边界轮 r1 在完整轮序列下标 0，严格早于窗口最旧轮 r2（下标 1）→ 可注入
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt()))
                .thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("早期轮次梗概", "r1"));

        AssembledContextDTO assembled =
                trimmedAssembler().assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND);

        assertEquals("早期轮次梗概", assembled.summary());
        assertEquals(4, assembled.history().size(), "回放历史仍从窗口最旧轮 r2 开始");
        assertEquals("c".repeat(60), assembled.history().get(0).content());
        assertEquals("f".repeat(60), assembled.history().get(3).content());
    }

    @Test
    @DisplayName("窗口调大后边界轮回归窗口：不注入（宁缺勿重）")
    void skipsSummaryWhenBoundaryBackInsideWindow() {
        // 手算：同样 3 轮裁到窗口 [r2, r3]，但边界轮 r2 就是窗口最旧轮（下标 1 ≥ 起点 1），
        // 摘要盖住的轮次正在被回放，重复注入会造成重叠 → 不注入
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt()))
                .thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("早期轮次梗概", "r2"));

        assertNull(trimmedAssembler().assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).summary());
    }

    @Test
    @DisplayName("窗口起点为 0（全会话都在窗口内）：不注入")
    void skipsSummaryWhenWindowStartsAtZero() {
        // 手算：只有 2 个完整轮，远小于 6 轮上限且总字符 8 ≤ 2000 → 窗口起点 0，无窗口外轮次；
        // 即便库里存有摘要与边界（边界 r1 也在完整轮序列内）仍不可注入
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of(
                row("r1", "USER", "问题一", "DONE"),
                row("r1", "ASSISTANT", "回答一", "DONE"),
                row("r2", "USER", "问题二", "DONE"),
                row("r2", "ASSISTANT", "回答二", "DONE")));
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("早期轮次梗概", "r1"));

        assertNull(assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).summary());
    }

    @Test
    @DisplayName("会话无摘要：不注入")
    void skipsSummaryWhenConversationHasNone() {
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt()))
                .thenReturn(threeRoundsOf120Chars());

        assertNull(assembler.assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).summary());
    }

    @Test
    @DisplayName("边界轮在窗口最旧轮之后（id 比较不成立）：不注入")
    void skipsSummaryWhenBoundaryNotBeforeWindowOldestById() {
        // 手算：窗口 [r2, r3]，库中边界 roundId 不在本序列；DB 按消息 id 判定边界轮
        // 并不严格早于窗口最旧轮 r2（防御脏数据）→ 不注入
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt()))
                .thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("早期轮次梗概", "round-late"));
        when(messageMapper.isRoundStrictlyBefore(CONVERSATION_ID, "round-late", "r2"))
                .thenReturn(Boolean.FALSE);

        assertNull(trimmedAssembler().assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).summary());
    }

    @Test
    @DisplayName("边界轮查不到（脏数据）：按不可注入处理（宁缺勿重）")
    void skipsSummaryWhenBoundaryRoundMissingFromSequence() {
        // 手算：窗口 [r2, r3]，库中边界 roundId 不在本序列且 DB 查无此轮（返回 NULL）→ 不注入
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt()))
                .thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("早期轮次梗概", "round-gone"));
        when(messageMapper.isRoundStrictlyBefore(CONVERSATION_ID, "round-gone", "r2"))
                .thenReturn(null);

        assertNull(trimmedAssembler().assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND).summary());
    }

    @Test
    @DisplayName("摘要滞后超出装配行窗口：按消息 id 判定仍注入")
    void injectsSummaryWhenBoundaryLagsBeyondRowWindow() {
        // 手算：3 个 120 字符轮，maxChars=250 → 窗口 [r2, r3]，最旧轮 r2；
        // 边界轮 r0 不在本序列（摘要合并连续跳过后边界滞后，行窗口装不下历史轮次）——
        // 按 spec 仍应注入：DB 按消息 id 判定 r0 严格早于 r2 → 可注入
        when(messageMapper.listRecentByConversation(eq(CONVERSATION_ID), anyInt()))
                .thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("早期轮次梗概", "r0"));
        when(messageMapper.isRoundStrictlyBefore(CONVERSATION_ID, "r0", "r2"))
                .thenReturn(Boolean.TRUE);

        AssembledContextDTO assembled =
                trimmedAssembler().assembleWithSummary(CONVERSATION_ID, USER_ID, CURRENT_ROUND);

        assertEquals("早期轮次梗概", assembled.summary(), "滞后越多的会话越需要摘要，不得因行窗口截断而静默失效");
        assertEquals(4, assembled.history().size(), "回放历史不变：仍从窗口最旧轮 r2 开始");
    }

    /** 字符预算 250 的装配器：3 个 120 字符轮会裁掉最旧一轮，窗口起点为 1（供边界判定场景复用） */
    private ConversationHistoryAssembler trimmedAssembler() {
        return new ConversationHistoryAssembler(messageMapper, conversationMapper,
                new ObjectMapper(), 6, 250);
    }

    /** 3 个完整轮，每轮 USER+ASSISTANT 各 60 字符（共 360 字符，用于触发字符预算裁剪） */
    private static List<MessageEntity> threeRoundsOf120Chars() {
        return List.of(
                row("r1", "USER", "a".repeat(60), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(60), "DONE"),
                row("r2", "USER", "c".repeat(60), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(60), "DONE"),
                row("r3", "USER", "e".repeat(60), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(60), "DONE"));
    }

    private static ConversationEntity conversation(String summary, String boundaryRoundId) {
        return new ConversationEntity(CONVERSATION_ID, USER_ID, "会话", "ACTIVE",
                "2026-11-01 10:00:00", "2026-11-01 10:00:00", summary, boundaryRoundId);
    }

    private static MessageEntity assistantRow(String roundId, String content,
                                           String routeJson, String planJson) {
        return new MessageEntity("msg-" + roundId + "-ASSISTANT", CONVERSATION_ID, roundId,
                "ASSISTANT", content, "DONE", "[]", "deepseek-chat", 0, null,
                "2026-11-01 10:00:01", routeJson, planJson);
    }

    private static MessageEntity row(String roundId, String role, String content, String status) {
        return new MessageEntity("msg-" + roundId + "-" + role, CONVERSATION_ID, roundId, role,
                content, status, null, null, null, null, "2026-11-01 10:00:00", null, null);
    }
}
