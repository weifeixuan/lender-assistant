package com.lender.assistant.conversation.mapper;

import com.lender.assistant.conversation.model.ConversationRow;
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
    List<ConversationRow> listActiveByUser(@Param("userId") String userId);

    /** 归属校验：会话存在、未删除且属于该用户；任一不满足返回 null */
    ConversationRow findActiveByConversationIdAndUser(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId);

    /** 软删：仅把 ACTIVE 且属于该用户的会话置为 DELETED，返回受影响行数 */
    int softDelete(@Param("conversationId") String conversationId,
                   @Param("userId") String userId);

    /** 刷新会话最近更新时间；返回 0 表示会话已不可见（如流式中被删除） */
    int touch(@Param("conversationId") String conversationId);

    /** 会话是否仍为 ACTIVE（流式结束落库前确认未被删除） */
    int countActiveByConversationId(@Param("conversationId") String conversationId);
}
