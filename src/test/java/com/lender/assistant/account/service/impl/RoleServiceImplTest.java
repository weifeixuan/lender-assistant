package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppRoleMapper;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppRoleSummaryEntity;
import com.lender.assistant.account.model.request.CreateRoleRequest;
import com.lender.assistant.account.model.request.UpdateRoleRequest;
import com.lender.assistant.account.model.response.PermissionResponse;
import com.lender.assistant.account.model.response.RoleResponse;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色管理契约：内置保护、引用计数、权限码解析、重名不区分大小写。
 */
class RoleServiceImplTest {

    private static final String ROLE_ID = "role-viewer";
    private static final String ADMIN_ROLE_ID = "role-admin";

    private AppRoleMapper roleMapper;
    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        roleMapper = mock(AppRoleMapper.class);
        service = new RoleServiceImpl(roleMapper);
    }

    @Test
    @DisplayName("角色列表：返回名称、描述、权限点、引用账户数与内置标记")
    void listReturnsRoleSummaries() {
        when(roleMapper.listAllWithAccountCount()).thenReturn(List.of(
                new AppRoleSummaryEntity(ADMIN_ROLE_ID, "系统管理员", "全部权限", true,
                        "2026-11-13 10:00:00", "2026-11-13 10:00:00", 1),
                new AppRoleSummaryEntity(ROLE_ID, "文档只读", null, false,
                        "2026-11-13 10:01:00", "2026-11-13 10:01:00", 3)));
        when(roleMapper.listPermissionCodes(ROLE_ID))
                .thenReturn(List.of("knowledge.documents.view"));
        when(roleMapper.listPermissionCodes(ADMIN_ROLE_ID)).thenReturn(List.of());

        List<RoleResponse> roles = service.listRoles();

        assertEquals("系统管理员", roles.get(0).name());
        assertTrue(roles.get(0).builtin(), "内置标记如实返回");
        assertEquals(1, roles.get(0).accountCount(), "返回引用账户数");
        assertEquals(List.of("knowledge.documents.view"), roles.get(1).permissionCodes());
        assertFalse(roles.get(1).builtin());
        assertEquals(3, roles.get(1).accountCount());
    }

    @Test
    @DisplayName("权限点目录：返回 5 个固定权限点（码 + 中文名）")
    void listPermissionsReturnsCatalog() {
        List<PermissionResponse> permissions = service.listPermissions();

        assertEquals(5, permissions.size());
        assertEquals("knowledge.documents.view", permissions.get(0).code());
        assertEquals("查看文档", permissions.get(0).name());
        assertEquals("settings.roles.manage", permissions.get(4).code());
    }

    @Test
    @DisplayName("新建角色：重名（不区分大小写）返回 409")
    void createRejectsDuplicateName() {
        when(roleMapper.findByNameIgnoreCase("文档只读"))
                .thenReturn(new AppRoleEntity(ROLE_ID, "文档只读", null, false, null, null));

        BizException exception = assertThrows(BizException.class, () -> service.createRole(
                new CreateRoleRequest("文档只读", null, List.of("knowledge.documents.view"))));

        assertEquals(ErrorCode.ROLE_NAME_EXISTS, exception.errorCode());
        verify(roleMapper, never()).insert(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("新建角色：未识别的权限码返回 400，不静默忽略")
    void createRejectsUnknownPermission() {
        when(roleMapper.findByNameIgnoreCase("自定义角色")).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.createRole(
                new CreateRoleRequest("自定义角色", null, List.of("unknown.permission"))));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
        verify(roleMapper, never()).insert(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("新建角色：落库角色与权限点组合")
    void createStoresRoleAndPermissions() {
        when(roleMapper.findByNameIgnoreCase("文档只读")).thenReturn(null);
        when(roleMapper.findById(anyString())).thenReturn(
                new AppRoleEntity(ROLE_ID, "文档只读", null, false, null, null));
        when(roleMapper.listPermissionCodes(ROLE_ID))
                .thenReturn(List.of("knowledge.documents.view"));

        RoleResponse created = service.createRole(new CreateRoleRequest("文档只读", "只读",
                List.of("knowledge.documents.view")));

        verify(roleMapper).insert(anyString(), eq("文档只读"), eq("只读"), eq(false));
        verify(roleMapper).insertPermission(anyString(), eq("knowledge.documents.view"));
        assertEquals("文档只读", created.name());
        assertEquals(List.of("knowledge.documents.view"), created.permissionCodes());
    }

    @Test
    @DisplayName("编辑内置角色：一律拒绝并返回 ROLE_PROTECTED")
    void updateBuiltinIsRejected() {
        when(roleMapper.findById(ADMIN_ROLE_ID)).thenReturn(
                new AppRoleEntity(ADMIN_ROLE_ID, "系统管理员", null, true, null, null));

        BizException exception = assertThrows(BizException.class, () -> service.updateRole(ADMIN_ROLE_ID,
                new UpdateRoleRequest("改名", null, List.of())));

        assertEquals(ErrorCode.ROLE_PROTECTED, exception.errorCode());
        verify(roleMapper, never()).update(anyString(), anyString(), any());
        verify(roleMapper, never()).deletePermissions(anyString());
    }

    @Test
    @DisplayName("编辑自定义角色：整体替换权限点")
    void updateReplacesPermissions() {
        when(roleMapper.findById(ROLE_ID)).thenReturn(
                new AppRoleEntity(ROLE_ID, "文档只读", null, false, null, null));
        when(roleMapper.findByNameIgnoreCase("文档维护")).thenReturn(null);
        when(roleMapper.listPermissionCodes(ROLE_ID))
                .thenReturn(List.of("knowledge.documents.write"));

        service.updateRole(ROLE_ID, new UpdateRoleRequest("文档维护", "可写",
                List.of("knowledge.documents.write")));

        verify(roleMapper).update(ROLE_ID, "文档维护", "可写");
        verify(roleMapper).deletePermissions(ROLE_ID);
        verify(roleMapper).insertPermission(ROLE_ID, "knowledge.documents.write");
    }

    @Test
    @DisplayName("编辑角色：改成已被占用的名称返回 409")
    void updateRejectsDuplicateName() {
        when(roleMapper.findById(ROLE_ID)).thenReturn(
                new AppRoleEntity(ROLE_ID, "文档只读", null, false, null, null));
        when(roleMapper.findByNameIgnoreCase("系统管理员"))
                .thenReturn(new AppRoleEntity(ADMIN_ROLE_ID, "系统管理员", null, true, null, null));

        BizException exception = assertThrows(BizException.class, () -> service.updateRole(ROLE_ID,
                new UpdateRoleRequest("系统管理员", null, List.of())));

        assertEquals(ErrorCode.ROLE_NAME_EXISTS, exception.errorCode());
    }

    @Test
    @DisplayName("删除内置角色：拒绝")
    void deleteBuiltinIsRejected() {
        when(roleMapper.findById(ADMIN_ROLE_ID)).thenReturn(
                new AppRoleEntity(ADMIN_ROLE_ID, "系统管理员", null, true, null, null));

        BizException exception = assertThrows(BizException.class,
                () -> service.deleteRole(ADMIN_ROLE_ID));

        assertEquals(ErrorCode.ROLE_PROTECTED, exception.errorCode());
        verify(roleMapper, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("删除仍被引用的角色：409 并在文案中给出引用账户数")
    void deleteReferencedRoleIsRejected() {
        when(roleMapper.findById(ROLE_ID)).thenReturn(
                new AppRoleEntity(ROLE_ID, "文档只读", null, false, null, null));
        when(roleMapper.countAccountsByRole(ROLE_ID)).thenReturn(3);

        BizException exception = assertThrows(BizException.class,
                () -> service.deleteRole(ROLE_ID));

        assertEquals(ErrorCode.ROLE_IN_USE, exception.errorCode());
        assertTrue(exception.getMessage().contains("3"), "拒绝文案必须给出引用账户数");
        verify(roleMapper, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("删除未被引用的自定义角色：先清权限点再删角色")
    void deleteUnreferencedRole() {
        when(roleMapper.findById(ROLE_ID)).thenReturn(
                new AppRoleEntity(ROLE_ID, "文档只读", null, false, null, null));
        when(roleMapper.countAccountsByRole(ROLE_ID)).thenReturn(0);

        service.deleteRole(ROLE_ID);

        ArgumentCaptor<String> roleIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(roleMapper).deletePermissions(roleIdCaptor.capture());
        verify(roleMapper).deleteById(roleIdCaptor.capture());
        assertEquals(List.of(ROLE_ID, ROLE_ID), roleIdCaptor.getAllValues());
    }
}
