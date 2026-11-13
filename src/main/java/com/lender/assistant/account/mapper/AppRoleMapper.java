package com.lender.assistant.account.mapper;

import com.lender.assistant.account.model.entity.AppRoleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    /** 按标识批量取角色，用于校验账户选中的角色是否存在 */
    List<AppRoleEntity> listByIds(@Param("roleIds") List<String> roleIds);

    /** 一批角色涉及的权限码（去重），用于判定角色集合是否含管理能力 */
    List<String> listPermissionCodesByRoles(@Param("roleIds") List<String> roleIds);
}
