package com.lender.assistant.account.mapper;

import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * app_user 与账户-角色/权限读取的唯一 SQL 落点。
 *
 * 用户名比较一律走 {@code lower(username)}，与唯一索引口径一致。
 */
@Mapper
public interface AppUserMapper {

    /** 按用户名登录查找（不区分大小写） */
    AppUserEntity findByUsernameIgnoreCase(@Param("username") String username);

    /** 按账户标识查（当前账户 / 认证上下文） */
    AppUserEntity findById(@Param("userId") String userId);

    /** 新增账户；时间列走数据库默认值 */
    int insert(@Param("userId") String userId,
               @Param("username") String username,
               @Param("displayName") String displayName,
               @Param("passwordHash") String passwordHash,
               @Param("status") String status);

    /** 账户拥有的角色（含 builtin 标记） */
    List<AppRoleEntity> listRolesByUser(@Param("userId") String userId);

    /** 账户经角色关联到的权限点码（去重）；未知码由服务层拒绝 */
    List<String> listPermissionCodesByUser(@Param("userId") String userId);
}
