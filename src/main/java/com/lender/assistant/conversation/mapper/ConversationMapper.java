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
}
