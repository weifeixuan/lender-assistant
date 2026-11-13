package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppRoleMapper;
import com.lender.assistant.account.mapper.AppUserMapper;
import com.lender.assistant.account.mapper.UserRoleMapper;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppUserEntity;
import com.lender.assistant.account.service.AuthBootstrapService;
import com.lender.assistant.account.enums.AccountStatus;
import com.lender.assistant.common.constant.AuthConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 启动自举：打破「无人能进账户管理」的冷启动死锁。
 *
 * 幂等口径：角色按 lower(name) 查、账户按 lower(username) 查；
 * 已存在不重复创建、不覆盖已有口令；绑定用 ON CONFLICT DO NOTHING。
 */
@Service
public class AuthBootstrapServiceImpl implements AuthBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapServiceImpl.class);

    private static final String BUILTIN_ROLE_DESCRIPTION = "内置角色：隐式拥有全部权限点";

    private final AppUserMapper appUserMapper;
    private final AppRoleMapper appRoleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AuthBootstrapServiceImpl(AppUserMapper appUserMapper,
                                    AppRoleMapper appRoleMapper,
                                    UserRoleMapper userRoleMapper,
                                    PasswordEncoder passwordEncoder,
                                    @Value("${auth.bootstrap.admin-username:admin}") String adminUsername,
                                    @Value("${auth.bootstrap.admin-password:}") String adminPassword) {
        this.appUserMapper = appUserMapper;
        this.appRoleMapper = appRoleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public String bootstrap() {
        AppRoleEntity builtinRole = ensureBuiltinRole();

        if (adminPassword == null || adminPassword.isBlank()) {
            // 漏配口令不应导致服务起不来：只警告并跳过建号
            log.warn("未配置 AUTH_BOOTSTRAP_ADMIN_PASSWORD，跳过初始管理员创建");
            return null;
        }

        String username = adminUsername == null || adminUsername.isBlank()
                ? "admin" : adminUsername.trim();
        AppUserEntity admin = appUserMapper.findByUsernameIgnoreCase(username);
        if (admin == null) {
            String userId = UUID.randomUUID().toString();
            appUserMapper.insert(userId, username,
                    AuthConstants.BOOTSTRAP_ADMIN_DISPLAY_NAME,
                    passwordEncoder.encode(adminPassword),
                    AccountStatus.ENABLED.code());
            admin = new AppUserEntity(userId, username, AuthConstants.BOOTSTRAP_ADMIN_DISPLAY_NAME,
                    null, AccountStatus.ENABLED.code(), null, null);
            log.info("已创建初始管理员账户: {}", username);
        } else {
            // 已存在时绝不覆盖口令：环境变量只是首次创建用的引导值
            log.info("初始管理员账户已存在，跳过创建: {}", username);
        }
        userRoleMapper.insertIfAbsent(admin.userId(), builtinRole.roleId());
        return admin.userId();
    }

    private AppRoleEntity ensureBuiltinRole() {
        AppRoleEntity existing = appRoleMapper.findByNameIgnoreCase(AuthConstants.BUILTIN_ADMIN_ROLE_NAME);
        if (existing != null) {
            return existing;
        }
        String roleId = UUID.randomUUID().toString();
        appRoleMapper.insert(roleId, AuthConstants.BUILTIN_ADMIN_ROLE_NAME,
                BUILTIN_ROLE_DESCRIPTION, true);
        return new AppRoleEntity(roleId, AuthConstants.BUILTIN_ADMIN_ROLE_NAME,
                BUILTIN_ROLE_DESCRIPTION, true, null, null);
    }
}
