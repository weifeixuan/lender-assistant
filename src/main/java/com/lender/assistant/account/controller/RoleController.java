package com.lender.assistant.account.controller;

import com.lender.assistant.account.model.request.CreateRoleRequest;
import com.lender.assistant.account.model.request.UpdateRoleRequest;
import com.lender.assistant.account.model.response.PermissionResponse;
import com.lender.assistant.account.model.response.RoleResponse;
import com.lender.assistant.account.service.RoleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理与权限点目录。鉴权见 AuthInterceptor：
 * 读角色列表对「管理账户」也开放（账户页选角色），写操作与权限点目录只给「管理角色」。
 */
@RestController
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/api/roles")
    public List<RoleResponse> listRoles() {
        return roleService.listRoles();
    }

    @GetMapping("/api/permissions")
    public List<PermissionResponse> listPermissions() {
        return roleService.listPermissions();
    }

    @PostMapping("/api/roles")
    public RoleResponse createRole(@RequestBody CreateRoleRequest request) {
        return roleService.createRole(request);
    }

    @PutMapping("/api/roles/{roleId}")
    public RoleResponse updateRole(@PathVariable String roleId,
                                   @RequestBody UpdateRoleRequest request) {
        return roleService.updateRole(roleId, request);
    }

    @DeleteMapping("/api/roles/{roleId}")
    public void deleteRole(@PathVariable String roleId) {
        roleService.deleteRole(roleId);
    }
}
