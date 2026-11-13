package com.lender.assistant.account.service;

import com.lender.assistant.account.model.request.CreateRoleRequest;
import com.lender.assistant.account.model.request.UpdateRoleRequest;
import com.lender.assistant.account.model.response.PermissionResponse;
import com.lender.assistant.account.model.response.RoleResponse;

import java.util.List;

/**
 * 角色管理：列表、权限点目录、新建、编辑、删除。
 *
 * 内置「系统管理员」只读；仍被账户引用的自定义角色不可删除。
 */
public interface RoleService {

    List<RoleResponse> listRoles();

    /** 权限点目录：v1 固定 5 个，码与中文名均来自枚举 */
    List<PermissionResponse> listPermissions();

    RoleResponse createRole(CreateRoleRequest request);

    RoleResponse updateRole(String roleId, UpdateRoleRequest request);

    void deleteRole(String roleId);
}
