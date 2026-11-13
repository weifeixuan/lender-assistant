package com.lender.assistant.account.mapper;

import com.lender.assistant.account.model.entity.AppRoleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * app_role 的唯一 SQL 落点。角色名比较一律走 {@code lower(name)}。
 */
@Mapper
public interface AppRoleMapper {

    /** 按角色名查（不区分大小写），用于自举幂等与重名校验 */
    AppRoleEntity findByNameIgnoreCase(@Param("name") String name);

    /** 新增角色；时间列走数据库默认值 */
    int insert(@Param("roleId") String roleId,
               @Param("name") String name,
               @Param("description") String description,
               @Param("builtin") boolean builtin);
}
