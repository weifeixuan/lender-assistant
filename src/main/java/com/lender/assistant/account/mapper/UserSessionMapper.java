package com.lender.assistant.account.mapper;

import com.lender.assistant.account.model.entity.UserSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * user_session 的唯一 SQL 落点。
 *
 * 过期判定放在 SQL（{@code expire_time > now()}）：数据库时区可能与应用时区不同，
 * 让数据库做绝对时间比较，避免把 timestamptz 渲染字符串后再解析造成偏差。
 */
@Mapper
public interface UserSessionMapper {

    /** 写入会话（只存令牌摘要） */
    int insert(@Param("tokenHash") String tokenHash,
               @Param("userId") String userId,
               @Param("expireTime") LocalDateTime expireTime);

    /** 按摘要查有效会话（连账户取状态）；不存在或已过期返回 null */
    UserSessionEntity findByTokenHash(@Param("tokenHash") String tokenHash);

    /** 登出：只删当前令牌对应会话 */
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    /** 登录时顺手清理该账户已过期会话，不引入定时任务 */
    int deleteExpiredByUser(@Param("userId") String userId);

    /** 失效该账户全部会话：重置口令、禁用账户 */
    int deleteByUser(@Param("userId") String userId);

    /** 失效该账户除当前令牌外的会话：改自己的口令后其他设备退出 */
    int deleteByUserExceptToken(@Param("userId") String userId,
                                @Param("tokenHash") String tokenHash);
}
