package com.lender.assistant.resilience.mapper;

import com.lender.assistant.resilience.model.LlmCallLogRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * llm_call_log 的唯一 INSERT 落点（§6.1：SQL 只存在于 mapper 层）。
 * 不含 @Transactional、不含 try/catch——旁路降级（写库失败不反噬业务）留给调用侧 LlmCallLogger。
 */
@Mapper
public interface LlmCallLogMapper {

    int insert(@Param("log") LlmCallLogRecord log);
}
