package com.lender.assistant.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * user_role 的唯一 SQL 落点。绑定必须幂等：启动自举会重复执行。
 */
@Mapper
public interface UserRoleMapper {

    /** 绑定账户与角色；已存在则忽略，返回受影响行数 */
    int insertIfAbsent(@Param("userId") String userId, @Param("roleId") String roleId);

    /** 清空账户的角色绑定：改角色集合时整体替换 */
    int deleteByUser(@Param("userId") String userId);
}
