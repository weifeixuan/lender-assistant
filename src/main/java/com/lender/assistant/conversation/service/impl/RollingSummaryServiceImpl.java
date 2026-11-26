package com.lender.assistant.conversation.service.impl;

import com.lender.assistant.common.component.LocalLockComponent;
import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.common.constant.QaPrompts;
import com.lender.assistant.common.util.ThreadPoolFactory;
import com.lender.assistant.conversation.client.SummaryModelClient;
import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.dto.CompleteRoundDTO;
import com.lender.assistant.conversation.model.entity.ConversationEntity;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import com.lender.assistant.conversation.service.ConversationHistoryAssembler;
import com.lender.assistant.conversation.service.RollingSummaryService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 滚动摘要服务（PRD §2.4 + §4）：异步触发、检测被裁前缀、滚动合并、原子写回。
 *
 * 异步执行（PRD §2.4）：摘要任务独占单线程池（ThreadPoolFactory 装配：命名 daemon 线程 +
 * 有界队列），摘要慢不反压回答主链路；队列占满按 AbortPolicy 快速失败，提交侧 catch 住
 * 按跳过处理（§7.1 禁 Executors / 无界队列）。任务先 tryAcquire 会话锁——拿不到（新一轮
 * 正在回答）跳过让路，边界未推进、下轮检测幂等补齐。Bean 持有池，@PreDestroy 关停。
 *
 * 边界实算复用 {@link ConversationHistoryAssembler} 的完整轮判定与窗口起点算法（§3.6：
 * 同一算法只允许一处实现），不在本类复制第二套；窗口上限配置两处共用同一组 @Value 键。
 * 被裁轮次原文拼装格式：每轮「用户：…\n助手：…」，轮与轮之间空行分隔。
 */
@Service
public class RollingSummaryServiceImpl implements RollingSummaryService {

    private static final Logger log = LoggerFactory.getLogger(RollingSummaryServiceImpl.class);

    /** 被裁轮次原文的角色标签与轮间分隔（PRD §4 第 4 步，格式契约以单测断言锁定） */
    private static final String USER_LABEL = "用户：";
    private static final String ASSISTANT_LABEL = "助手：";
    private static final String ROUND_SEPARATOR = "\n\n";

    /** 单线程：摘要会话级串行；有界队列容量取小值，积压即拒绝跳过（下轮幂等补齐） */
    private static final int POOL_SIZE = 1;
    private static final int QUEUE_CAPACITY = 8;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final String THREAD_NAME_PREFIX = "rolling-summary";

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final SummaryModelClient summaryClient;
    private final ConversationHistoryAssembler historyAssembler;
    private final LocalLockComponent lockRegistry;
    private final ExecutorService summaryExecutor;
    private final int maxChars;

    /** Spring 装配入口：类有两个构造器（本构造器 + 测试注入 executor 的重载），必须显式指定 */
    @Autowired
    public RollingSummaryServiceImpl(MessageMapper messageMapper,
                                     ConversationMapper conversationMapper,
                                     SummaryModelClient summaryClient,
                                     ConversationHistoryAssembler historyAssembler,
                                     LocalLockComponent lockRegistry,
                                     @Value("${rag.qa.summary.max-chars:500}") int maxChars) {
        this(messageMapper, conversationMapper, summaryClient, historyAssembler, lockRegistry, maxChars,
                ThreadPoolFactory.create(POOL_SIZE, POOL_SIZE, QUEUE_CAPACITY,
                        KEEP_ALIVE_SECONDS, THREAD_NAME_PREFIX));
    }

    /** 测试可注入受控 executor（同线程直执行 / 已 shutdown 的池验证拒绝路径） */
    public RollingSummaryServiceImpl(MessageMapper messageMapper,
                                     ConversationMapper conversationMapper,
                                     SummaryModelClient summaryClient,
                                     ConversationHistoryAssembler historyAssembler,
                                     LocalLockComponent lockRegistry,
                                     int maxChars,
                                     ExecutorService summaryExecutor) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.summaryClient = summaryClient;
        this.historyAssembler = historyAssembler;
        this.lockRegistry = lockRegistry;
        this.summaryExecutor = summaryExecutor;
        this.maxChars = maxChars;
    }

    @Override
    public void submitAfterRound(String conversationId, String userId) {
        try {
            summaryExecutor.execute(() -> runWithLock(conversationId, userId));
        } catch (RejectedExecutionException e) {
            log.info("摘要任务提交被拒绝（队列已满或池已关停），跳过本轮: conversationId={}",
                    conversationId);
        }
    }

    /** 持锁执行摘要：拿不到锁不 release（锁是别人持有的）；finally 释放自己拿到的锁 */
    private void runWithLock(String conversationId, String userId) {
        String lockKey = conversationLockKey(conversationId);
        if (!lockRegistry.tryAcquire(lockKey)) {
            log.info("会话锁被占用（新一轮正在回答），跳过本轮摘要: conversationId={}", conversationId);
            return;
        }
        try {
            detectAndMerge(conversationId, userId);
        } catch (RuntimeException e) {
            // 防御兜底：detectAndMerge 契约自身静默降级不抛，这里再兜一层——
            // 异步任务异常不允许炸掉工作线程或沿提交路径外泄
            log.warn("摘要任务执行异常，已跳过: conversationId={}", conversationId, e);
        } finally {
            lockRegistry.release(lockKey);
        }
    }

    /** 与 QaServiceImpl.conversationLockKey 重复（§3.2）：同一会话锁的两处取键点，前缀收敛于 MessageConstants */
    private static String conversationLockKey(String conversationId) {
        return MessageConstants.CONVERSATION_LOCK_PREFIX + conversationId;
    }

    @Override
    public void detectAndMerge(String conversationId, String userId) {
        // 读会话全部消息（PRD §4 第 1 步）：被裁前缀可超出回放装配用的最近窗口，必须全量实算
        List<MessageEntity> rows = messageMapper.listByConversation(conversationId);
        // currentRoundId 传 null：触发发生在该轮收尾之后，刚完成的 DONE 轮也要参与边界实算
        List<CompleteRoundDTO> rounds = historyAssembler.completeRounds(rows, null);
        int windowStart = historyAssembler.windowStart(rounds);
        if (windowStart == 0) {
            // 首问/首轮：全会话都在窗口内，没有被裁前缀，幂等出口
            return;
        }
        String newBoundary = rounds.get(windowStart - 1).roundId();
        ConversationEntity conversation =
                conversationMapper.findActiveByConversationIdAndUser(conversationId, userId);
        if (conversation == null) {
            // 会话已删除或非本人：静默跳过（与 updateSummary 的 ACTIVE 条件同语义）
            return;
        }
        if (newBoundary.equals(conversation.summaryBoundaryRoundId())) {
            // 边界未变：无新增被裁轮次（rule 轮触发 / 重复触发），幂等出口，不调模型
            return;
        }
        int startIndex = startIndexAfterBoundary(rounds, conversation.summaryBoundaryRoundId());
        if (startIndex >= windowStart) {
            // 窗口调大后旧摘要轮回归窗口：无新增被裁轮次，不追加动作（PRD §2.3）
            return;
        }
        String roundsText = roundsText(rounds, startIndex, windowStart);
        try {
            String summary = summaryClient.complete(
                    QaPrompts.summaryPrompt(conversation.summary(), roundsText));
            if (summary == null || summary.isBlank()) {
                // 空正文按失败处理：updateSummary 两列同写，空值会把旧摘要抹掉
                log.warn("摘要模型返回空正文，保留旧摘要: conversationId={}", conversationId);
                return;
            }
            // 两列原子写回（只更 summary 两列、WHERE status='ACTIVE'）；模型失败走 catch 天然不写回
            conversationMapper.updateSummary(conversationId, truncate(summary), newBoundary);
        } catch (Exception e) {
            // 静默降级（PRD §2.5）：保留旧摘要（边界不推进），下轮回答完成后的检测幂等补齐
            log.warn("滚动摘要生成或写回失败，保留旧摘要: conversationId={}", conversationId, e);
        }
    }

    /**
     * 合并输入的起点：库中边界之后的第一个完整轮下标（天然覆盖间隙轮）；
     * 无边界（首次合并）或边界轮不在序列（理论上不发生：消息不删）时从最旧一轮起全量并入。
     */
    private int startIndexAfterBoundary(List<CompleteRoundDTO> rounds, String storedBoundary) {
        if (storedBoundary == null || storedBoundary.isBlank()) {
            return 0;
        }
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).roundId().equals(storedBoundary)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 被裁轮次原文：每轮「用户：…\n助手：…」，轮间空行分隔（格式契约见单测断言） */
    private String roundsText(List<CompleteRoundDTO> rounds, int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            CompleteRoundDTO round = rounds.get(i);
            if (text.length() > 0) {
                text.append(ROUND_SEPARATOR);
            }
            text.append(USER_LABEL).append(round.user().content())
                    .append("\n").append(ASSISTANT_LABEL).append(round.assistant().content());
        }
        return text.toString();
    }

    /** 硬截断到 max-chars（PRD §2.5）：摘要只是背景参考，截断断句可接受，不做二次模型调用 */
    private String truncate(String summary) {
        return summary.length() > maxChars ? summary.substring(0, maxChars) : summary;
    }

    @PreDestroy
    void shutdown() {
        summaryExecutor.shutdownNow();
    }

    /** 线程池是否已关停（测试与诊断用） */
    boolean isExecutorShutdown() {
        return summaryExecutor.isShutdown();
    }
}
