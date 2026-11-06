package com.lender.assistant.conversation.mapper;

import com.lender.assistant.conversation.model.dto.MessageInsertDTO;
import com.lender.assistant.conversation.model.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * message 表的唯一读写落点。
 *
 * sources 以 JSON 文本传入/取出，SQL 侧负责 jsonb 转换；不引 JSON 类型处理器。
 */
@Mapper
public interface MessageMapper {

    /** 插入一条消息；sourcesJson 可为 null（用户消息） */
    int insert(@Param("message") MessageInsertDTO message);

    /** 按会话读取全部消息，id ASC 即会话内稳定时序 */
    List<MessageEntity> listByConversation(@Param("conversationId") String conversationId);

    /** 助手消息终态回填路由与计划快照；两个 JSON 文本可为 null */
    int updateRouteAndPlan(@Param("conversationId") String conversationId,
                           @Param("roundId") String roundId,
                           @Param("routeJson") String routeJson,
                           @Param("executionPlanJson") String executionPlanJson);
}
