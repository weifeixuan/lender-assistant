package com.lender.assistant.conversation.mapper;

import com.lender.assistant.conversation.model.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * conversation 表的唯一读写落点（§6.1：SQL 只存在于 mapper 层）。
 *
 * 无 @Transactional：事务边界留在 Service（§6.11）。
 */
@Mapper
public interface ConversationMapper {

    /** 插入会话；status 与时间列走数据库默认值（ACTIVE / now()） */
    int insert(@Param("conversationId") String conversationId,
               @Param("userId") String userId,
               @Param("title") String title);

    /** 当前用户未删除的会话，按 update_time DESC, id DESC */
    List<ConversationEntity> listActiveByUser(@Param("userId") String userId);

    /** 归属校验：会话存在、未删除且属于该用户；任一不满足返回 null */
    ConversationEntity findActiveByConversationIdAndUser(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId);

    /** 软删：仅把 ACTIVE 且属于该用户的会话置为 DELETED，返回受影响行数 */
    int softDelete(@Param("conversationId") String conversationId,
                   @Param("userId") String userId);

    /** 刷新 ACTIVE 会话的最近更新时间；已删会话命中 0 行，时间线不被推后 */
    int touch(@Param("conversationId") String conversationId);

    /**
     * 写回滚动摘要：只更 summary 两列、不 touch update_time（会话列表时间线不被后台任务推后）；
     * 带 ACTIVE 条件，提交到执行之间会话被软删则命中 0 行。summary 与边界同写同空。
     */
    int updateSummary(@Param("conversationId") String conversationId,
                      @Param("summary") String summary,
                      @Param("summaryBoundaryRoundId") String summaryBoundaryRoundId);

    /**
     * 历史归属迁移：只把仍属于 {@code fromUserId} 的会话改为 {@code toUserId}。
     * 重复执行时来源账户已无会话，自然命中 0 行；已删除会话同样迁移。
     */
    int migrateOwner(@Param("fromUserId") String fromUserId,
                     @Param("toUserId") String toUserId);
}
