package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppUserMapper;
import com.lender.assistant.account.mapper.UserSessionMapper;
import com.lender.assistant.account.model.dto.AuthenticatedAccountDTO;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppUserEntity;
import com.lender.assistant.account.model.entity.UserSessionEntity;
import com.lender.assistant.account.model.response.AuthAccountResponse;
import com.lender.assistant.account.model.response.LoginResponse;
import com.lender.assistant.account.model.enums.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 认证服务契约：凭据错误三种情况文案一致、库中只存令牌摘要、权限并集与内置角色短路、
 * 登出只失效当前令牌。
 */
class AuthServiceImplTest {

    private static final String USER_ID = "6f1d0d0e-0000-4000-8000-000000000001";
    private static final String PASSWORD = "password123";
    private static final int TTL_DAYS = 7;

    private AppUserMapper userMapper;
    private UserSessionMapper sessionMapper;
    private PasswordEncoder encoder;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(AppUserMapper.class);
        sessionMapper = mock(UserSessionMapper.class);
        encoder = new BCryptPasswordEncoder();
        service = new AuthServiceImpl(userMapper, sessionMapper, encoder, TTL_DAYS);
    }

    @Test
    @DisplayName("正确凭据登录：返回令牌、到期时间与账户权限并集")
    void loginReturnsTokenAndAccount() throws Exception {
        when(userMapper.findByUsernameIgnoreCase("admin")).thenReturn(enabledUser());
        when(userMapper.listRolesByUser(USER_ID)).thenReturn(List.of(
                new AppRoleEntity("r1", "系统管理员", "内置", true, null, null)));
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> expireCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        LoginResponse response = service.login("admin", PASSWORD);

        verify(sessionMapper).deleteExpiredByUser(USER_ID);
        verify(sessionMapper).insert(hashCaptor.capture(), eq(USER_ID), expireCaptor.capture());
        assertFalse(hashCaptor.getValue().isBlank(), "令牌摘要不能为空");
        assertEquals(sha256(response.token()), hashCaptor.getValue(),
                "库里必须只存令牌的 SHA-256 摘要");
        assertNotEquals(response.token(), hashCaptor.getValue(), "不得把令牌原文落库");
        assertTrue(expireCaptor.getValue().isAfter(LocalDateTime.now().plusDays(TTL_DAYS - 1)),
                "到期时间应约为当前时间 + 7 天");
        assertTrue(response.expireTime().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "到期时间格式必须是 YYYY-MM-DD HH:mm:ss");
        assertEquals("admin", response.user().username(), "返回登录账户");
        assertEquals(5, response.user().permissions().size(), "内置管理员短路拥有全部权限点");
        verify(userMapper, never()).listPermissionCodesByUser(anyString());
    }

    @Test
    @DisplayName("账户不存在、口令错误、账户禁用：统一 401 且文案完全一致")
    void credentialFailuresShareOneMessage() {
        String expected = "用户名或密码错误";

        when(userMapper.findByUsernameIgnoreCase("ghost")).thenReturn(null);
        BizException missing = assertThrows(BizException.class,
                () -> service.login("ghost", PASSWORD));

        when(userMapper.findByUsernameIgnoreCase("admin")).thenReturn(enabledUser());
        BizException wrongPassword = assertThrows(BizException.class,
                () -> service.login("admin", "wrong-password"));

        AppUserEntity disabled = new AppUserEntity(USER_ID, "admin", "管理员",
                encoder.encode(PASSWORD), "DISABLED", null, null);
        when(userMapper.findByUsernameIgnoreCase("admin")).thenReturn(disabled);
        BizException disabledAccount = assertThrows(BizException.class,
                () -> service.login("admin", PASSWORD));

        for (BizException exception : List.of(missing, wrongPassword, disabledAccount)) {
            assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode(), "凭据错误必须是 401");
            assertEquals(expected, exception.getMessage(), "三种失败不得给出可区分的提示");
        }
    }

    @Test
    @DisplayName("令牌不存在：401 登录已失效")
    void unknownTokenIsRejected() {
        when(sessionMapper.findByTokenHash(anyString())).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.authenticate("bogus-token"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode());
        assertEquals("登录已失效，请重新登录", exception.getMessage());
    }

    @Test
    @DisplayName("账户被禁用：即使令牌存在也立即 401")
    void disabledAccountInvalidatesSession() {
        when(sessionMapper.findByTokenHash(anyString())).thenReturn(new UserSessionEntity(
                "hash", USER_ID, "admin", "管理员", "DISABLED", "2999-01-01 00:00:00"));

        BizException exception = assertThrows(BizException.class,
                () -> service.authenticate("token"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode(),
                "禁用后下一次请求即失效");
    }

    @Test
    @DisplayName("有效令牌返回多角色权限并集与令牌摘要")
    void authenticateUnionsPermissions() throws Exception {
        when(sessionMapper.findByTokenHash(anyString())).thenReturn(new UserSessionEntity(
                "hash", USER_ID, "admin", "管理员", "ENABLED", "2999-01-01 00:00:00"));
        when(userMapper.listRolesByUser(USER_ID)).thenReturn(List.of(
                new AppRoleEntity("r1", "文档只读", "只读", false, null, null),
                new AppRoleEntity("r2", "对话用户", "聊天", false, null, null)));
        when(userMapper.listPermissionCodesByUser(USER_ID)).thenReturn(List.of(
                "knowledge.chat.use", "knowledge.documents.view"));

        AuthenticatedAccountDTO account = service.authenticate("token");

        assertEquals(USER_ID, account.userId(), "账户标识来自会话");
        assertEquals(sha256("token"), account.tokenHash(),
                "登出需要与库里会话主键一致的令牌摘要");
        assertEquals(List.of("knowledge.chat.use", "knowledge.documents.view"),
                account.permissions(), "多角色权限取并集");
    }

    @Test
    @DisplayName("角色权限表出现未识别的码：拒绝而不是静默忽略")
    void unknownPermissionCodeIsRejected() {
        when(sessionMapper.findByTokenHash(anyString())).thenReturn(new UserSessionEntity(
                "hash", USER_ID, "admin", "管理员", "ENABLED", "2999-01-01 00:00:00"));
        when(userMapper.listRolesByUser(USER_ID)).thenReturn(List.of(
                new AppRoleEntity("r1", "自定义", null, false, null, null)));
        when(userMapper.listPermissionCodesByUser(USER_ID)).thenReturn(List.of("knowledge.unknown"));

        assertThrows(IllegalStateException.class, () -> service.authenticate("token"),
                "未知权限码属于数据完整性问题，必须显式失败");
    }

    @Test
    @DisplayName("登出只删除当前令牌对应会话")
    void logoutDeletesCurrentTokenOnly() {
        service.logout("current-hash");

        verify(sessionMapper).deleteByTokenHash("current-hash");
    }

    @Test
    @DisplayName("当前账户返回角色与权限点")
    void currentAccountReturnsAuthorities() {
        when(userMapper.findById(USER_ID)).thenReturn(enabledUser());
        when(userMapper.listRolesByUser(USER_ID)).thenReturn(List.of(
                new AppRoleEntity("r1", "文档只读", "只读", false, null, null)));
        when(userMapper.listPermissionCodesByUser(USER_ID)).thenReturn(
                List.of(PermissionCode.KNOWLEDGE_DOCUMENTS_VIEW.code()));

        AuthAccountResponse account = service.currentAccount(USER_ID);

        assertEquals("admin", account.username(), "返回账户用户名");
        assertEquals(1, account.roles().size(), "返回角色列表");
        assertEquals(List.of("knowledge.documents.view"), account.permissions(), "返回权限点");
    }

    private AppUserEntity enabledUser() {
        return new AppUserEntity(USER_ID, "admin", "管理员",
                encoder.encode(PASSWORD), "ENABLED", "2026-11-13 10:00:00", "2026-11-13 10:00:00");
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
