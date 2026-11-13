package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppRoleMapper;
import com.lender.assistant.account.mapper.AppUserMapper;
import com.lender.assistant.account.mapper.UserRoleMapper;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 启动自举契约：缺口令只跳过建号；已有账户不重复创建、不覆盖口令；角色与绑定幂等。
 */
class AuthBootstrapServiceImplTest {

    private static final String ADMIN_ROLE_ID = "role-admin";

    private AppUserMapper userMapper;
    private AppRoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        userMapper = mock(AppUserMapper.class);
        roleMapper = mock(AppRoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        encoder = new BCryptPasswordEncoder();
        when(roleMapper.findByNameIgnoreCase("系统管理员")).thenReturn(
                new AppRoleEntity(ADMIN_ROLE_ID, "系统管理员", "内置", true, null, null));
    }

    @Test
    @DisplayName("未提供初始管理员口令：只确保内置角色，不创建账户")
    void missingPasswordSkipsAdminCreation() {
        AuthBootstrapServiceImpl service = new AuthBootstrapServiceImpl(
                userMapper, roleMapper, userRoleMapper, encoder, "admin", "");

        String adminUserId = service.bootstrap();

        assertNull(adminUserId, "未创建管理员时返回 null，供历史会话迁移跳过");
        verify(userMapper, never()).insert(anyString(), anyString(), anyString(),
                anyString(), anyString());
        verify(userRoleMapper, never()).insertIfAbsent(anyString(), anyString());
    }

    @Test
    @DisplayName("内置角色不存在时创建一次，已存在则不重复创建")
    void createsBuiltinRoleIdempotently() {
        when(roleMapper.findByNameIgnoreCase("系统管理员")).thenReturn(null);
        AuthBootstrapServiceImpl service = new AuthBootstrapServiceImpl(
                userMapper, roleMapper, userRoleMapper, encoder, "admin", "password123");

        service.bootstrap();

        verify(roleMapper).insert(anyString(), eq("系统管理员"), anyString(), eq(true));
    }

    @Test
    @DisplayName("初始管理员不存在时创建并绑定内置角色")
    void createsAdminAndBindsRole() {
        when(userMapper.findByUsernameIgnoreCase("admin")).thenReturn(null);
        AuthBootstrapServiceImpl service = new AuthBootstrapServiceImpl(
                userMapper, roleMapper, userRoleMapper, encoder, "admin", "password123");

        String adminUserId = service.bootstrap();

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userMapper).insert(userIdCaptor.capture(), eq("admin"), anyString(),
                hashCaptor.capture(), eq("ENABLED"));
        assertEquals(userIdCaptor.getValue(), adminUserId,
                "返回的账户标识应与落库主键一致，供后续会话迁移使用");
        assertFalse(userIdCaptor.getValue().isBlank(), "账户主键必须是应用生成的 UUID");
        assertTrue(hashCaptor.getValue().startsWith("$2"), "口令必须以 BCrypt 摘要落库");
        assertTrue(encoder.matches("password123", hashCaptor.getValue()), "摘要应能校验原始口令");
        verify(userRoleMapper).insertIfAbsent(userIdCaptor.getValue(), ADMIN_ROLE_ID);
    }

    @Test
    @DisplayName("初始管理员已存在：不重复创建、不覆盖口令，只补绑定")
    void existingAdminIsNotOverwritten() {
        when(userMapper.findByUsernameIgnoreCase("admin")).thenReturn(new AppUserEntity(
                "existing-id", "admin", "管理员", "$2a$10$existing", "ENABLED", null, null));
        AuthBootstrapServiceImpl service = new AuthBootstrapServiceImpl(
                userMapper, roleMapper, userRoleMapper, encoder, "admin", "new-password");

        String adminUserId = service.bootstrap();

        assertEquals("existing-id", adminUserId, "已有管理员时返回其账户标识");
        verify(userMapper, never()).insert(anyString(), anyString(), anyString(),
                anyString(), anyString());
        verify(userRoleMapper).insertIfAbsent("existing-id", ADMIN_ROLE_ID);
        verify(roleMapper, never()).insert(anyString(), anyString(), anyString(), anyBoolean());
    }
}
