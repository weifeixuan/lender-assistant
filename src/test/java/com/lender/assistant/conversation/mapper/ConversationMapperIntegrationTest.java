package com.lender.assistant.conversation.mapper;

import com.lender.assistant.conversation.model.entity.ConversationEntity;
import com.lender.assistant.testutil.PostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * conversation 摘要两列与写回的真实 SQL 验证（Testcontainers + pgvector 镜像，票 01）。
 *
 * <p>覆盖纯单测与文件契约测试看不出来的数据库语义：</p>
 * <ul>
 *   <li>docs/schema.sql 在真实 PostgreSQL 16 上可执行（含两条 ADD COLUMN IF NOT EXISTS 幂等迁移）——
 *       容器初始化失败整个测试类起不来，即迁移写错的直接证据；</li>
 *   <li>updateSummary 只写 summary 两列，update_time 不被后台任务推后（会话列表按它排序）；</li>
 *   <li>已软删会话写回命中 0 行（与读路径的 ACTIVE 过滤同语义，宁可跳过不复活）。</li>
 * </ul>
 */
@Tag("docker")
@Transactional
class ConversationMapperIntegrationTest extends PostgresContainerSupport {

    private static final String CONVERSATION = "it-summary-conv";
    private static final String USER = "it-user";

    @Autowired
    private ConversationMapper conversationMapper;

    @Test
    void updateSummaryWritesTwoColumnsAndKeepsUpdateTimeUntouched() {
        conversationMapper.insert(CONVERSATION, USER, "摘要写回");
        ConversationEntity before =
                conversationMapper.findActiveByConversationIdAndUser(CONVERSATION, USER);

        int updated = conversationMapper.updateSummary(
                CONVERSATION, "用户早期确认关注小额产品线", "round-3");

        assertEquals(1, updated, "ACTIVE 会话写回必须命中 1 行");
        ConversationEntity after =
                conversationMapper.findActiveByConversationIdAndUser(CONVERSATION, USER);
        assertEquals("用户早期确认关注小额产品线", after.summary());
        assertEquals("round-3", after.summaryBoundaryRoundId());
        assertEquals(before.updateTime(), after.updateTime(),
                "摘要写回不许更新 update_time，否则后台任务会推后会话列表时间线");
        assertEquals("ACTIVE", after.status());
    }

    @Test
    void updateSummaryOnSoftDeletedConversationHitsZeroRows() {
        conversationMapper.insert(CONVERSATION, USER, "软删后写回");
        assertEquals(1, conversationMapper.softDelete(CONVERSATION, USER));

        int updated = conversationMapper.updateSummary(CONVERSATION, "迟到摘要", "round-9");

        assertEquals(0, updated, "DELETED 会话写回命中 0 行，摘要不复活已删会话");
        assertNull(conversationMapper.findActiveByConversationIdAndUser(CONVERSATION, USER));
    }

    @Test
    void updateSummaryTwiceOverwritesBothColumnsAtomically() {
        conversationMapper.insert(CONVERSATION, USER, "重复写回");
        conversationMapper.updateSummary(CONVERSATION, "第一版摘要", "round-1");

        conversationMapper.updateSummary(CONVERSATION, "第二版摘要", "round-4");

        ConversationEntity row =
                conversationMapper.findActiveByConversationIdAndUser(CONVERSATION, USER);
        assertEquals("第二版摘要", row.summary(), "滚动合并是覆盖写，旧摘要不残留");
        assertEquals("round-4", row.summaryBoundaryRoundId(),
                "正文与边界同写同空，不允许出现正文新边界旧的中间态");
        assertTrue(row.summary().length() > 0 && row.summaryBoundaryRoundId() != null);
    }
}
