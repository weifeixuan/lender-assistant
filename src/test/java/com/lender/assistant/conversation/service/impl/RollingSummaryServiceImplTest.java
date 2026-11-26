package com.lender.assistant.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.component.LocalLockComponent;
import com.lender.assistant.common.constant.QaPrompts;
import com.lender.assistant.common.util.ThreadPoolFactory;
import com.lender.assistant.conversation.client.SummaryModelClient;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.entity.ConversationEntity;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.RollingSummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 滚动合并服务单测（PRD §4）：边界实算复用装配器，断言值全部手算核对。
 *
 * 手算口径：装配器 maxRounds=6 / maxChars=250；每轮 USER+ASSISTANT 各 60 字符时
 * 3 个完整轮共 360 字符 > 250 → 窗口起点 = 1（r1 被裁），新边界 = r1；
 * 每轮各 200 字符（USER+ASSISTANT 各 100）时 4 轮窗口起点 = 3（r1–r3 被裁），新边界 = r3。
 */
class RollingSummaryServiceImplTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String USER_ID = "user-1";

    private MessageMapper messageMapper;
    private ConversationMapper conversationMapper;
    private SummaryModelClient summaryClient;
    private LocalLockComponent lockRegistry;
    private RollingSummaryService summaryService;
    private RollingSummaryServiceImpl summaryServiceImpl;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        summaryClient = mock(SummaryModelClient.class);
        lockRegistry = new LocalLockComponent();
        // 真实装配器：边界实算与完整轮判定复用同一套算法，不 mock
        ConversationHistoryAssembler assembler = new ConversationHistoryAssembler(
                mock(MessageMapper.class), mock(ConversationMapper.class), new ObjectMapper(), 6, 250);
        summaryServiceImpl = new RollingSummaryServiceImpl(
                messageMapper, conversationMapper, summaryClient, assembler, lockRegistry, 500);
        summaryService = summaryServiceImpl;
    }

    @AfterEach
    void tearDown() {
        summaryServiceImpl.shutdown();
    }

    @Test
    @DisplayName("异步提交——锁空闲：持锁执行 detectAndMerge，finally 释放锁")
    void asyncSubmissionAcquiresLockRunsAndReleases() {
        RollingSummaryServiceImpl spied = newAsyncImplWithStubbedDetect();

        spied.submitAfterRound(CONVERSATION_ID, USER_ID);

        verify(spied).detectAndMerge(CONVERSATION_ID, USER_ID);
        assertFalse(lockRegistry.isBusy(LOCK_KEY), "任务结束后锁必须已释放");
    }

    @Test
    @DisplayName("异步提交——锁被占（新一轮正在回答）：跳过且不执行，不 release 别人的锁")
    void asyncSubmissionSkipsWhenLockBusy() {
        RollingSummaryServiceImpl spied = newAsyncImplWithStubbedDetect();
        assertTrue(lockRegistry.tryAcquire(LOCK_KEY), "预占会话锁，模拟新一轮正在回答");

        spied.submitAfterRound(CONVERSATION_ID, USER_ID);

        verify(spied, never()).detectAndMerge(anyString(), anyString());
        assertTrue(lockRegistry.isBusy(LOCK_KEY), "拿不到锁不得释放别人持有的锁");
    }

    @Test
    @DisplayName("异步提交——服务抛异常：finally 兜底释放锁，不把会话锁死")
    void asyncSubmissionReleasesLockEvenWhenServiceThrows() {
        RollingSummaryServiceImpl spied = newAsyncImplWithStubbedDetect();
        doThrow(new RuntimeException("模拟摘要服务内部异常"))
                .when(spied).detectAndMerge(anyString(), anyString());

        assertDoesNotThrow(() -> spied.submitAfterRound(CONVERSATION_ID, USER_ID));
        assertFalse(lockRegistry.isBusy(LOCK_KEY), "异常路径同样必须释放锁");
    }

    @Test
    @DisplayName("异步提交——线程池拒绝（队列已满或关停）：静默跳过，不执行不抛异常")
    void asyncSubmissionRejectedIsSilentlySkipped() {
        ThreadPoolExecutor rejecting = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        rejecting.shutdown();
        RollingSummaryServiceImpl spied = spy(new RollingSummaryServiceImpl(
                messageMapper, conversationMapper, summaryClient,
                new ConversationHistoryAssembler(mock(MessageMapper.class), mock(ConversationMapper.class),
                        new ObjectMapper(), 6, 250),
                lockRegistry, 500, rejecting));
        doNothing().when(spied).detectAndMerge(anyString(), anyString());

        assertDoesNotThrow(() -> spied.submitAfterRound(CONVERSATION_ID, USER_ID),
                "拒绝时静默跳过（AbortPolicy 异常必须 catch 住）");
        verify(spied, never()).detectAndMerge(anyString(), anyString());
    }

    @Test
    @DisplayName("@PreDestroy 关停：shutdown 后线程池终止，不再受理新任务")
    void shutdownStopsExecutor() {
        RollingSummaryServiceImpl pooled = new RollingSummaryServiceImpl(
                messageMapper, conversationMapper, summaryClient,
                new ConversationHistoryAssembler(mock(MessageMapper.class), mock(ConversationMapper.class),
                        new ObjectMapper(), 6, 250),
                lockRegistry, 500,
                ThreadPoolFactory.create(1, 1, 1, 60, "rolling-summary-test"));

        pooled.shutdown();

        assertTrue(pooled.isExecutorShutdown(), "Bean 关停必须关闭持有的线程池");
    }

    /** 锁路径专用的最小实现：detectAndMerge 打桩为空操作，只验证提交包装与锁行为 */
    private RollingSummaryServiceImpl newAsyncImplWithStubbedDetect() {
        RollingSummaryServiceImpl spied = spy(new RollingSummaryServiceImpl(
                messageMapper, conversationMapper, summaryClient,
                new ConversationHistoryAssembler(mock(MessageMapper.class), mock(ConversationMapper.class),
                        new ObjectMapper(), 6, 250),
                lockRegistry, 500, sameThreadExecutor()));
        doNothing().when(spied).detectAndMerge(anyString(), anyString());
        return spied;
    }

    /** 会话锁完整键（与被测服务取键口径一致：前缀来自 MessageConstants） */
    private static final String LOCK_KEY =
            com.lender.assistant.common.constant.MessageConstants.CONVERSATION_LOCK_PREFIX + CONVERSATION_ID;

    /** 同线程直执行 executor：execute 即 run，让提交包装逻辑同步完成、断言免等待 */
    private static ExecutorService sameThreadExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }

    @Test
    @DisplayName("新边界与库中已存边界相同：幂等出口，不调模型也不写回（rule 轮触发走这里）")
    void sameBoundaryExitsWithoutModelCall() {
        // 手算：3 个完整轮各 120 字符 + 中间一轮 rule 轮（整轮跳过，不进完整轮序列）
        // → 完整轮序列 r1、r2、r3，360 字符 > 250 → 窗口起点 1，新边界 = r1；库中边界已是 r1 → 幂等出口
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "a".repeat(60), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(60), "DONE"),
                row("rule-round", "USER", "你好", "DONE"),
                row("rule-round", "ASSISTANT", "固定提示", "DONE", "rule"),
                row("r2", "USER", "c".repeat(60), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(60), "DONE"),
                row("r3", "USER", "e".repeat(60), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(60), "DONE")));
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", "r1"));

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        verify(summaryClient, never()).complete(anyString());
        verify(conversationMapper, never()).updateSummary(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("窗口起点为 0（首问/首轮全会话都在窗口内）：幂等出口，不调模型")
    void windowStartZeroExitsWithoutModelCall() {
        // 手算：2 个完整轮共 240 字符 ≤ 250 且轮数 2 < 6 → 窗口起点 0，无被裁前缀；
        // 即便库中已有摘要与边界，也不触发任何动作
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "a".repeat(60), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(60), "DONE"),
                row("r2", "USER", "c".repeat(60), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(60), "DONE")));
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", "r1"));

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        verify(summaryClient, never()).complete(anyString());
        verify(conversationMapper, never()).updateSummary(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("首次合并（无旧摘要）：合并输入 = 被裁轮次原文，格式为「用户：…\\n助手：…」逐轮拼接")
    void mergesCroppedRoundsWithoutExistingSummary() {
        // 手算：3 个完整轮各 120 字符 → 窗口起点 1，新边界 = r1；库中无边界 → 全部被裁轮次（r1）并入
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation(null, null));
        when(summaryClient.complete(anyString())).thenReturn("新摘要");

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        String expectedRoundsText = "用户：" + "a".repeat(60) + "\n助手：" + "b".repeat(60);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(summaryClient).complete(promptCaptor.capture());
        assertEquals(QaPrompts.summaryPrompt(null, expectedRoundsText), promptCaptor.getValue(),
                "prompt 必须由 QaPrompts.summaryPrompt 渲染，轮次原文按「用户：…\\n助手：…」格式拼装");
        verify(conversationMapper).updateSummary(CONVERSATION_ID, "新摘要", "r1");
    }

    @Test
    @DisplayName("有旧摘要 + 间隙轮：库中边界早于窗口最旧轮，边界之后所有被裁轮次（含间隙轮）都并入合并输入")
    void mergesGapRoundsAfterStoredBoundary() {
        // 手算：4 个完整轮各 200 字符，maxChars=250 → 窗口起点 3（r1–r3 被裁），新边界 = r3；
        // 库中边界 = r1（下标 0）→ 从 r2 起并入：间隙轮 r2、r3 都进合并输入，已覆盖的 r1 不重复
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "a".repeat(100), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(100), "DONE"),
                row("r2", "USER", "c".repeat(100), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(100), "DONE"),
                row("r3", "USER", "e".repeat(100), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(100), "DONE"),
                row("r4", "USER", "g".repeat(100), "DONE"),
                row("r4", "ASSISTANT", "h".repeat(100), "DONE")));
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", "r1"));
        when(summaryClient.complete(anyString())).thenReturn("新摘要");

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        String expectedRoundsText = "用户：" + "c".repeat(100) + "\n助手：" + "d".repeat(100)
                + "\n\n用户：" + "e".repeat(100) + "\n助手：" + "f".repeat(100);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(summaryClient).complete(promptCaptor.capture());
        assertEquals(QaPrompts.summaryPrompt("旧摘要", expectedRoundsText), promptCaptor.getValue());
        verify(conversationMapper).updateSummary(CONVERSATION_ID, "新摘要", "r3");
    }

    @Test
    @DisplayName("模型返回超长正文：硬截断到 max-chars（600 字 → 库里 500 字），不做二次调用")
    void truncatesOverlongSummaryToMaxChars() {
        // 手算：窗口起点 1，新边界 = r1；模型返回 600 字符 → 硬截断为前 500 字符写回
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation(null, null));
        when(summaryClient.complete(anyString())).thenReturn("x".repeat(600));

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        verify(summaryClient, times(1)).complete(anyString());
        verify(conversationMapper).updateSummary(CONVERSATION_ID, "x".repeat(500), "r1");
    }

    @Test
    @DisplayName("模型调用失败：WARN 降级不向上抛，不写回，旧摘要原样保留（updateSummary 零交互）")
    void modelFailureKeepsOldSummary() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", null));
        when(summaryClient.complete(anyString())).thenThrow(new RuntimeException("模拟模型超时"));

        assertDoesNotThrow(() -> summaryService.detectAndMerge(CONVERSATION_ID, USER_ID),
                "摘要失败静默降级，绝不挡回答主链路");
        verify(conversationMapper, never()).updateSummary(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("写回失败：同样静默降级不向上抛（旧摘要天然保留）")
    void writeBackFailureIsSilent() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", null));
        when(summaryClient.complete(anyString())).thenReturn("新摘要");
        when(conversationMapper.updateSummary(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("模拟写库失败"));

        assertDoesNotThrow(() -> summaryService.detectAndMerge(CONVERSATION_ID, USER_ID));
    }

    @Test
    @DisplayName("模型返回空白正文：视为失败，不写回（避免空值把旧摘要抹掉）")
    void blankModelOutputSkipsWriteBack() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", null));
        when(summaryClient.complete(anyString())).thenReturn("  ");

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        verify(conversationMapper, never()).updateSummary(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("会话已删除或非本人（归属校验失败）：静默跳过，不调模型")
    void invisibleConversationSkipsDetection() {
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(threeRoundsOf120Chars());
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(null);

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        verify(summaryClient, never()).complete(anyString());
        verify(conversationMapper, never()).updateSummary(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("窗口调大后旧摘要轮回归窗口：无新增被裁轮次，不调模型不写回（PRD §2.3 不追加动作）")
    void enlargedWindowWithNoNewRoundsSkipsMerge() {
        // 手算：4 个完整轮各 200 字符 → 窗口起点 3，新边界 = r3；
        // 库中边界 = r4（比新边界更新：窗口调大后 r4 回归窗口）→ 边界之后无被裁轮次 → 不追加动作
        when(messageMapper.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                row("r1", "USER", "a".repeat(100), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(100), "DONE"),
                row("r2", "USER", "c".repeat(100), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(100), "DONE"),
                row("r3", "USER", "e".repeat(100), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(100), "DONE"),
                row("r4", "USER", "g".repeat(100), "DONE"),
                row("r4", "ASSISTANT", "h".repeat(100), "DONE")));
        when(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION_ID, USER_ID))
                .thenReturn(conversation("旧摘要", "r4"));

        summaryService.detectAndMerge(CONVERSATION_ID, USER_ID);

        verify(summaryClient, never()).complete(anyString());
        verify(conversationMapper, never()).updateSummary(anyString(), anyString(), anyString());
    }

    /** 3 个完整轮，每轮 USER+ASSISTANT 各 60 字符（共 360 字符，触发字符预算裁剪，窗口起点 1） */
    private static List<MessageEntity> threeRoundsOf120Chars() {
        return List.of(
                row("r1", "USER", "a".repeat(60), "DONE"),
                row("r1", "ASSISTANT", "b".repeat(60), "DONE"),
                row("r2", "USER", "c".repeat(60), "DONE"),
                row("r2", "ASSISTANT", "d".repeat(60), "DONE"),
                row("r3", "USER", "e".repeat(60), "DONE"),
                row("r3", "ASSISTANT", "f".repeat(60), "DONE"));
    }

    private static MessageEntity row(String roundId, String role, String content, String status) {
        return new MessageEntity("msg-" + roundId + "-" + role, CONVERSATION_ID, roundId, role,
                content, status, null, null, null, null, "2026-11-01 10:00:00", null, null);
    }

    private static MessageEntity row(String roundId, String role, String content,
                                     String status, String provider) {
        return new MessageEntity("msg-" + roundId + "-" + role, CONVERSATION_ID, roundId, role,
                content, status, null, provider, null, null, "2026-11-01 10:00:00", null, null);
    }

    private static ConversationEntity conversation(String summary, String boundaryRoundId) {
        return new ConversationEntity(CONVERSATION_ID, USER_ID, "会话", "ACTIVE",
                "2026-11-01 10:00:00", "2026-11-01 10:00:00", summary, boundaryRoundId);
    }
}
