package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppRoleMapper;
import com.lender.assistant.account.mapper.AppUserMapper;
import com.lender.assistant.account.mapper.UserRoleMapper;
import com.lender.assistant.account.mapper.UserSessionMapper;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppUserEntity;
import com.lender.assistant.account.model.request.ChangePasswordRequest;
import com.lender.assistant.account.model.request.CreateAccountRequest;
import com.lender.assistant.account.model.request.UpdateAccountRequest;
import com.lender.assistant.account.model.response.AccountResponse;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账户管理服务契约：票内五条硬约束 + 口令口径 + 会话失效范围。
 * 判定按「操作完成后的系统状态」计算，因此最后一个管理员的校验同时覆盖禁用与改角色两个入口。
 */
class AccountServiceImplTest {

    private static final String OPERATOR_ID = "6f1d0d0e-0000-4000-8000-000000000001";
    private static final String TARGET_ID = "6f1d0d0e-0000-4000-8000-000000000002";
    private static final String ADMIN_ROLE_ID = "role-admin";
    private static final String VIEWER_ROLE_ID = "role-viewer";
    private static final String TOKEN_HASH = "current-token-hash";
    private static final String PASSWORD = "password123";
    private static final String MANAGE_CODE = "settings.accounts.manage";

    private AppUserMapper userMapper;
    private AppRoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private UserSessionMapper sessionMapper;
    private PasswordEncoder encoder;
    private AccountServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(AppUserMapper.class);
        roleMapper = mock(AppRoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        sessionMapper = mock(UserSessionMapper.class);
        encoder = new BCryptPasswordEncoder();
        service = new AccountServiceImpl(userMapper, roleMapper, userRoleMapper, sessionMapper, encoder);
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("列表：返回用户名、显示名、角色、状态与创建时间")
    void listAccountsReturnsProfileRows() {
        when(userMapper.listAll()).thenReturn(List.of(user(TARGET_ID, "alice"), user(OPERATOR_ID, "admin")));
        when(userMapper.listRolesByUser(TARGET_ID)).thenReturn(List.of(
                new AppRoleEntity(VIEWER_ROLE_ID, "文档只读", null, false, null, null)));
        when(userMapper.listRolesByUser(OPERATOR_ID)).thenReturn(List.of(
                new AppRoleEntity(ADMIN_ROLE_ID, "系统管理员", null, true, null, null)));

        List<AccountResponse> accounts = service.listAccounts();

        assertEquals(List.of("alice", "admin"), accounts.stream().map(AccountResponse::username).toList(),
                "顺序沿用 SQL 的创建时间升序");
        assertEquals("文档只读", accounts.get(0).roles().get(0).name(), "返回角色摘要");
        assertEquals("ENABLED", accounts.get(0).status(), "返回状态");
        assertEquals("2026-11-13 10:00:00", accounts.get(0).createTime(), "返回创建时间");
    }

    // ---------- 新建 ----------

    @Test
    @DisplayName("新建：未指定角色直接 400")
    void createRequiresAtLeastOneRole() {
        BizException exception = assertThrows(BizException.class, () -> service.createAccount(
                new CreateAccountRequest("alice", "爱丽丝", PASSWORD, List.of())));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode(), "必须至少指定一个角色");
    }

    @Test
    @DisplayName("新建：用户名重复（不区分大小写）返回 409")
    void createRejectsDuplicateUsername() {
        when(userMapper.findByUsernameIgnoreCase("Alice")).thenReturn(user(TARGET_ID, "alice"));

        BizException exception = assertThrows(BizException.class, () -> service.createAccount(
                new CreateAccountRequest("Alice", "爱丽丝", PASSWORD, List.of(VIEWER_ROLE_ID))));

        assertEquals(ErrorCode.USERNAME_EXISTS, exception.errorCode());
        assertEquals(409, exception.errorCode().code());
        verify(userMapper, never()).insert(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("新建：角色标识不存在返回 400")
    void createRejectsUnknownRole() {
        when(userMapper.findByUsernameIgnoreCase("alice")).thenReturn(null);
        when(roleMapper.listByIds(List.of(VIEWER_ROLE_ID))).thenReturn(List.of());

        BizException exception = assertThrows(BizException.class, () -> service.createAccount(
                new CreateAccountRequest("alice", "爱丽丝", PASSWORD, List.of(VIEWER_ROLE_ID))));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
    }

    @Test
    @DisplayName("新建：口令按 BCrypt 落库并绑定所选角色")
    void createStoresHashedPasswordAndRoles() {
        when(userMapper.findByUsernameIgnoreCase("alice")).thenReturn(null);
        when(roleMapper.listByIds(List.of(VIEWER_ROLE_ID))).thenReturn(List.of(
                new AppRoleEntity(VIEWER_ROLE_ID, "文档只读", null, false, null, null)));
        when(userMapper.findById(anyString())).thenReturn(user(TARGET_ID, "alice"));
        when(userMapper.listRolesByUser(TARGET_ID)).thenReturn(List.of(
                new AppRoleEntity(VIEWER_ROLE_ID, "文档只读", null, false, null, null)));

        AccountResponse created = service.createAccount(
                new CreateAccountRequest("alice", "爱丽丝", PASSWORD, List.of(VIEWER_ROLE_ID)));

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userMapper).insert(userIdCaptor.capture(), eq("alice"), eq("爱丽丝"),
                hashCaptor.capture(), eq("ENABLED"));
        assertNotEquals(PASSWORD, hashCaptor.getValue(), "不得明文落库");
        assertTrue(encoder.matches(PASSWORD, hashCaptor.getValue()), "摘要必须能校验原口令");
        verify(userRoleMapper).insertIfAbsent(userIdCaptor.getValue(), VIEWER_ROLE_ID);
        assertEquals("alice", created.username());
        assertEquals("文档只读", created.roles().get(0).name());
    }

    // ---------- 改资料与角色 ----------

    @Test
    @DisplayName("改角色：角色集合不能为空")
    void updateRequiresAtLeastOneRole() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "alice"));

        BizException exception = assertThrows(BizException.class, () -> service.updateAccount(
                TARGET_ID, new UpdateAccountRequest("爱丽丝", List.of())));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
    }

    @Test
    @DisplayName("改资料：保存显示名与角色集合后返回更新结果")
    void updateReturnsUpdatedAccount() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "alice"));
        when(roleMapper.listByIds(List.of(VIEWER_ROLE_ID))).thenReturn(List.of(
                new AppRoleEntity(VIEWER_ROLE_ID, "文档只读", null, false, null, null)));
        when(roleMapper.listPermissionCodesByRoles(List.of(VIEWER_ROLE_ID))).thenReturn(List.of());
        when(userMapper.countEnabledAccountManagersExcluding(TARGET_ID, MANAGE_CODE)).thenReturn(1);

        AccountResponse updated = service.updateAccount(TARGET_ID,
                new UpdateAccountRequest("爱丽丝二号", List.of(VIEWER_ROLE_ID)));

        verify(userMapper).updateProfile(TARGET_ID, "爱丽丝二号");
        verify(userRoleMapper).deleteByUser(TARGET_ID);
        verify(userRoleMapper).insertIfAbsent(TARGET_ID, VIEWER_ROLE_ID);
        assertEquals("alice", updated.username(), "返回更新后的账户条目");
    }

    @Test
    @DisplayName("改角色：不能移除最后一个具备账户管理能力的账户")
    void updateRejectsRemovingLastAccountManager() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "admin"));
        when(roleMapper.listByIds(List.of(VIEWER_ROLE_ID))).thenReturn(List.of(
                new AppRoleEntity(VIEWER_ROLE_ID, "文档只读", null, false, null, null)));
        when(roleMapper.listPermissionCodesByRoles(List.of(VIEWER_ROLE_ID))).thenReturn(List.of());
        when(userMapper.countEnabledAccountManagersExcluding(TARGET_ID, MANAGE_CODE)).thenReturn(0);

        BizException exception = assertThrows(BizException.class, () -> service.updateAccount(
                TARGET_ID, new UpdateAccountRequest("管理员", List.of(VIEWER_ROLE_ID))));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
        verify(userMapper, never()).updateProfile(anyString(), anyString());
    }

    @Test
    @DisplayName("改角色：仍有其他管理员时允许降权")
    void updateAllowsDemotionWhenAnotherManagerExists() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "admin"));
        when(roleMapper.listByIds(List.of(VIEWER_ROLE_ID))).thenReturn(List.of(
                new AppRoleEntity(VIEWER_ROLE_ID, "文档只读", null, false, null, null)));
        when(roleMapper.listPermissionCodesByRoles(List.of(VIEWER_ROLE_ID))).thenReturn(List.of());
        when(userMapper.countEnabledAccountManagersExcluding(TARGET_ID, MANAGE_CODE)).thenReturn(1);

        service.updateAccount(TARGET_ID,
                new UpdateAccountRequest("管理员", List.of(VIEWER_ROLE_ID)));

        verify(userMapper).updateProfile(TARGET_ID, "管理员");
    }

    @Test
    @DisplayName("改角色：新角色仍含管理权限时无需校验他人")
    void updateKeepingManageCapabilityNeedsNoOtherManager() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "admin"));
        when(roleMapper.listByIds(List.of(ADMIN_ROLE_ID))).thenReturn(List.of(
                new AppRoleEntity(ADMIN_ROLE_ID, "系统管理员", null, true, null, null)));

        service.updateAccount(TARGET_ID,
                new UpdateAccountRequest("管理员", List.of(ADMIN_ROLE_ID)));

        verify(userMapper, never()).countEnabledAccountManagersExcluding(anyString(), anyString());
        verify(userMapper).updateProfile(TARGET_ID, "管理员");
    }

    @Test
    @DisplayName("账户不存在：404")
    void updateMissingAccountIsNotFound() {
        when(userMapper.findById(TARGET_ID)).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.updateAccount(
                TARGET_ID, new UpdateAccountRequest("爱丽丝", List.of(VIEWER_ROLE_ID))));

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode());
    }

    // ---------- 口令 ----------

    @Test
    @DisplayName("重置口令：少于 8 个字符返回 400")
    void resetPasswordRejectsTooShort() {
        BizException exception = assertThrows(BizException.class,
                () -> service.resetPassword(TARGET_ID, "1234567"));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
        verify(userMapper, never()).updatePassword(anyString(), anyString());
    }

    @Test
    @DisplayName("重置口令：超过 72 字节返回 400（中文按 UTF-8 计）")
    void resetPasswordRejectsOver72Bytes() {
        // 25 个中文字符 = 75 字节，字符数校验挡不住，必须按字节
        String tooLong = "密".repeat(25);

        BizException exception = assertThrows(BizException.class,
                () -> service.resetPassword(TARGET_ID, tooLong));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
    }

    @Test
    @DisplayName("重置口令：更新摘要并失效该账户全部会话")
    void resetPasswordInvalidatesAllSessions() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "alice"));

        service.resetPassword(TARGET_ID, "new-password-1");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userMapper).updatePassword(eq(TARGET_ID), hashCaptor.capture());
        assertTrue(encoder.matches("new-password-1", hashCaptor.getValue()), "落库的是新口令摘要");
        verify(sessionMapper).deleteByUser(TARGET_ID);
    }

    @Test
    @DisplayName("改自己的口令：旧口令不正确返回 400")
    void changeOwnPasswordRejectsWrongOldPassword() {
        when(userMapper.findById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "admin"));

        BizException exception = assertThrows(BizException.class, () -> service.changeOwnPassword(
                OPERATOR_ID, TOKEN_HASH, new ChangePasswordRequest("wrong-old", "new-password-1")));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
        verify(userMapper, never()).updatePassword(anyString(), anyString());
    }

    @Test
    @DisplayName("改自己的口令：只失效其他设备会话，保留当前令牌")
    void changeOwnPasswordKeepsCurrentToken() {
        when(userMapper.findById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "admin"));

        service.changeOwnPassword(OPERATOR_ID, TOKEN_HASH,
                new ChangePasswordRequest(PASSWORD, "new-password-1"));

        verify(userMapper).updatePassword(eq(OPERATOR_ID), anyString());
        verify(sessionMapper).deleteByUserExceptToken(OPERATOR_ID, TOKEN_HASH);
        verify(sessionMapper, never()).deleteByUser(anyString());
    }

    // ---------- 状态 ----------

    @Test
    @DisplayName("禁用：不能禁用自己")
    void disableSelfIsRejected() {
        when(userMapper.findById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, "admin"));

        BizException exception = assertThrows(BizException.class,
                () -> service.updateStatus(OPERATOR_ID, OPERATOR_ID, "DISABLED"));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
        verify(userMapper, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("禁用：不能禁用最后一个具备账户管理能力的账户")
    void disableLastAccountManagerIsRejected() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "admin"));
        when(userMapper.countEnabledAccountManagersExcluding(TARGET_ID, MANAGE_CODE)).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.updateStatus(OPERATOR_ID, TARGET_ID, "DISABLED"));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
        verify(userMapper, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("禁用：成功后立即失效该账户全部会话")
    void disableInvalidatesAllSessions() {
        when(userMapper.findById(TARGET_ID)).thenReturn(user(TARGET_ID, "alice"));
        when(userMapper.countEnabledAccountManagersExcluding(TARGET_ID, MANAGE_CODE)).thenReturn(2);

        service.updateStatus(OPERATOR_ID, TARGET_ID, "DISABLED");

        verify(userMapper).updateStatus(TARGET_ID, "DISABLED");
        verify(sessionMapper).deleteByUser(TARGET_ID);
    }

    @Test
    @DisplayName("状态值不合法：400")
    void invalidStatusIsRejected() {
        BizException exception = assertThrows(BizException.class,
                () -> service.updateStatus(OPERATOR_ID, TARGET_ID, "LOCKED"));

        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
    }

    private AppUserEntity user(String userId, String username) {
        return new AppUserEntity(userId, username, "管理员", encoder.encode(PASSWORD),
                "ENABLED", "2026-11-13 10:00:00", "2026-11-13 10:00:00");
    }
}
