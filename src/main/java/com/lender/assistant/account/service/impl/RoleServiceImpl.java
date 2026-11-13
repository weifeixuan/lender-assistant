package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppRoleMapper;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppRoleSummaryEntity;
import com.lender.assistant.account.model.request.CreateRoleRequest;
import com.lender.assistant.account.model.request.UpdateRoleRequest;
import com.lender.assistant.account.model.response.PermissionResponse;
import com.lender.assistant.account.model.response.RoleResponse;
import com.lender.assistant.account.service.RoleService;
import com.lender.assistant.common.constant.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 角色管理实现。内置角色在服务层硬拦截，页面置灰只是提示，后端才是兜底。
 */
@Service
public class RoleServiceImpl implements RoleService {

    private static final int ROLE_NAME_MAX_CODE_POINTS = 32;

    private final AppRoleMapper appRoleMapper;

    public RoleServiceImpl(AppRoleMapper appRoleMapper) {
        this.appRoleMapper = appRoleMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return appRoleMapper.listAllWithAccountCount().stream()
                .map(role -> new RoleResponse(role.roleId(), role.name(), role.description(),
                        appRoleMapper.listPermissionCodes(role.roleId()),
                        role.accountCount(), Boolean.TRUE.equals(role.builtin())))
                .toList();
    }

    @Override
    public List<PermissionResponse> listPermissions() {
        return Arrays.stream(PermissionCode.values())
                .map(permission -> new PermissionResponse(permission.code(), permission.desc()))
                .toList();
    }

    @Override
    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        String name = normalizeName(request.name());
        List<String> permissionCodes = resolvePermissions(request.permissionCodes());
        if (appRoleMapper.findByNameIgnoreCase(name) != null) {
            throw new BizException(ErrorCode.ROLE_NAME_EXISTS);
        }

        String roleId = UUID.randomUUID().toString();
        appRoleMapper.insert(roleId, name, request.description(), false);
        permissionCodes.forEach(code -> appRoleMapper.insertPermission(roleId, code));
        return toResponse(requireRole(roleId));
    }

    @Override
    @Transactional
    public RoleResponse updateRole(String roleId, UpdateRoleRequest request) {
        AppRoleEntity role = requireRole(roleId);
        if (Boolean.TRUE.equals(role.builtin())) {
            throw new BizException(ErrorCode.ROLE_PROTECTED);
        }
        String name = normalizeName(request.name());
        List<String> permissionCodes = resolvePermissions(request.permissionCodes());
        AppRoleEntity sameName = appRoleMapper.findByNameIgnoreCase(name);
        if (sameName != null && !sameName.roleId().equals(roleId)) {
            throw new BizException(ErrorCode.ROLE_NAME_EXISTS);
        }

        appRoleMapper.update(roleId, name, request.description());
        // 权限点整体替换：先清空再写入，避免残留已取消的勾选
        appRoleMapper.deletePermissions(roleId);
        permissionCodes.forEach(code -> appRoleMapper.insertPermission(roleId, code));
        return toResponse(requireRole(roleId));
    }

    @Override
    @Transactional
    public void deleteRole(String roleId) {
        AppRoleEntity role = requireRole(roleId);
        if (Boolean.TRUE.equals(role.builtin())) {
            throw new BizException(ErrorCode.ROLE_PROTECTED);
        }
        int accountCount = appRoleMapper.countAccountsByRole(roleId);
        if (accountCount > 0) {
            throw new BizException(ErrorCode.ROLE_IN_USE,
                    "角色仍被 " + accountCount + " 个账户使用");
        }
        appRoleMapper.deletePermissions(roleId);
        appRoleMapper.deleteById(roleId);
    }

    private RoleResponse toResponse(AppRoleEntity role) {
        return new RoleResponse(role.roleId(), role.name(), role.description(),
                appRoleMapper.listPermissionCodes(role.roleId()),
                appRoleMapper.countAccountsByRole(role.roleId()),
                Boolean.TRUE.equals(role.builtin()));
    }

    private AppRoleEntity requireRole(String roleId) {
        AppRoleEntity role = appRoleMapper.findById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    /** 未识别的权限码直接拒绝，不静默忽略——否则页面勾选与接口校验会出现两套真相 */
    private static List<String> resolvePermissions(List<String> permissionCodes) {
        if (permissionCodes == null) {
            return List.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : permissionCodes) {
            if (code == null || code.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "权限点不能为空");
            }
            try {
                codes.add(PermissionCode.fromCode(code.trim()).code());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "未识别的权限点: " + code);
            }
        }
        return List.copyOf(codes);
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > ROLE_NAME_MAX_CODE_POINTS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "角色名不能超过 " + ROLE_NAME_MAX_CODE_POINTS + " 个字符");
        }
        return trimmed;
    }
}
