package com.lender.assistant.account.mapper;

import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppRoleSummaryEntity;
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

    /** 按标识取单个角色，用于编辑/删除前的存在性与内置校验 */
    AppRoleEntity findById(@Param("roleId") String roleId);

    /** 角色列表附带引用账户数，按创建时间升序 */
    List<AppRoleSummaryEntity> listAllWithAccountCount();

    /** 改名称与描述（权限点另走替换） */
    int update(@Param("roleId") String roleId,
               @Param("name") String name,
               @Param("description") String description);

    /** 删除角色行；权限点由服务层先行清理 */
    int deleteById(@Param("roleId") String roleId);

    /** 引用该角色的账户数，删除前校验用 */
    int countAccountsByRole(@Param("roleId") String roleId);

    /** 单个角色的权限码 */
    List<String> listPermissionCodes(@Param("roleId") String roleId);

    /** 清空角色权限点：编辑时整体替换 */
    int deletePermissions(@Param("roleId") String roleId);

    /** 绑定角色权限点；重复绑定忽略 */
    int insertPermission(@Param("roleId") String roleId,
                         @Param("permissionCode") String permissionCode);
}
