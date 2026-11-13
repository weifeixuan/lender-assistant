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
import com.lender.assistant.account.model.response.RoleBriefResponse;
import com.lender.assistant.account.service.AccountService;
import com.lender.assistant.account.enums.AccountStatus;
import com.lender.assistant.account.enums.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.util.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 账户管理实现。
 *
 * 硬约束 2 的判定口径：按「操作完成后的系统状态」计算——改角色时看新角色集合是否仍有
 * 管理能力，禁用时排除目标账户后是否还有启用状态的管理员。两个入口都校验，避免
 * 只在禁用接口校验留下「改角色拿掉管理权限」的锁死漏洞。
 */
@Service
public class AccountServiceImpl implements AccountService {

    private static final String MANAGE_PERMISSION = PermissionCode.SETTINGS_ACCOUNTS_MANAGE.code();
    private static final String USERNAME_PATTERN = "[A-Za-z0-9_-]{3,32}";
    private static final int DISPLAY_NAME_MAX_CODE_POINTS = 32;

    private final AppUserMapper appUserMapper;
    private final AppRoleMapper appRoleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserSessionMapper userSessionMapper;
    private final PasswordEncoder passwordEncoder;

    public AccountServiceImpl(AppUserMapper appUserMapper,
                              AppRoleMapper appRoleMapper,
                              UserRoleMapper userRoleMapper,
                              UserSessionMapper userSessionMapper,
                              PasswordEncoder passwordEncoder) {
        this.appUserMapper = appUserMapper;
        this.appRoleMapper = appRoleMapper;
        this.userRoleMapper = userRoleMapper;
        this.userSessionMapper = userSessionMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts() {
        return appUserMapper.listAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        String username = normalizeUsername(request.username());
        String displayName = normalizeDisplayName(request.displayName());
        PasswordPolicy.validate(request.password());
        // 重名优先于角色校验：用户名冲突是用户最关心的失败原因
        if (appUserMapper.findByUsernameIgnoreCase(username) != null) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        List<AppRoleEntity> roles = requireRoles(request.roleIds());

        String userId = UUID.randomUUID().toString();
        appUserMapper.insert(userId, username, displayName,
                passwordEncoder.encode(request.password()), AccountStatus.ENABLED.code());
        roles.forEach(role -> userRoleMapper.insertIfAbsent(userId, role.roleId()));
        return toResponse(requireUser(userId));
    }

    @Override
    @Transactional
    public AccountResponse updateAccount(String userId, UpdateAccountRequest request) {
        String displayName = normalizeDisplayName(request.displayName());
        AppUserEntity user = requireUser(userId);
        List<AppRoleEntity> roles = requireRoles(request.roleIds());
        ensureNotLastAccountManagerAfterRoleChange(user, roles);

        appUserMapper.updateProfile(userId, displayName);
        // 角色集合整体替换：先清空旧绑定再绑定新集合，避免残留已移除的角色
        userRoleMapper.deleteByUser(userId);
        roles.forEach(role -> userRoleMapper.insertIfAbsent(userId, role.roleId()));
        return toResponse(requireUser(userId));
    }

    @Override
    @Transactional
    public void resetPassword(String userId, String newPassword) {
        PasswordPolicy.validate(newPassword);
        requireUser(userId);
        appUserMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        userSessionMapper.deleteByUser(userId);
    }

    @Override
    @Transactional
    public void changeOwnPassword(String userId, String currentTokenHash,
                                  ChangePasswordRequest request) {
        AppUserEntity user = requireUser(userId);
        if (request.oldPassword() == null
                || !passwordEncoder.matches(request.oldPassword(), user.passwordHash())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "旧口令不正确");
        }
        PasswordPolicy.validate(request.newPassword());
        appUserMapper.updatePassword(userId, passwordEncoder.encode(request.newPassword()));
        // 其他设备退出，当前这台仍可用
        userSessionMapper.deleteByUserExceptToken(userId, currentTokenHash);
    }

    @Override
    @Transactional
    public AccountResponse updateStatus(String operatorUserId, String userId, String status) {
        AccountStatus targetStatus = parseStatus(status);
        AppUserEntity user = requireUser(userId);

        if (targetStatus == AccountStatus.DISABLED) {
            if (userId.equals(operatorUserId)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "不能禁用自己");
            }
            if (AccountStatus.ENABLED.code().equals(user.status())
                    && appUserMapper.countEnabledAccountManagersExcluding(userId, MANAGE_PERMISSION) == 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "不能禁用最后一个具备账户管理能力的账户");
            }
            appUserMapper.updateStatus(userId, targetStatus.code());
            userSessionMapper.deleteByUser(userId);
        } else {
            appUserMapper.updateStatus(userId, targetStatus.code());
        }
        return toResponse(requireUser(userId));
    }

    /**
     * 改角色后的状态校验：目标账户仍启用且新角色仍含管理能力时无需看他人；
     * 否则必须还有另一个启用状态的管理员。
     * 目标账户本就禁用时，改角色不会造成新的锁死，跳过。
     */
    private void ensureNotLastAccountManagerAfterRoleChange(AppUserEntity user,
                                                            List<AppRoleEntity> roles) {
        if (!AccountStatus.ENABLED.code().equals(user.status())) {
            return;
        }
        if (hasAccountManageCapability(roles)) {
            return;
        }
        if (appUserMapper.countEnabledAccountManagersExcluding(user.userId(), MANAGE_PERMISSION) == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "不能移除最后一个具备账户管理能力的账户的管理权限");
        }
    }

    /** 内置角色短路为拥有全部权限点，与认证上下文的判定口径一致 */
    private boolean hasAccountManageCapability(List<AppRoleEntity> roles) {
        if (roles.stream().anyMatch(role -> Boolean.TRUE.equals(role.builtin()))) {
            return true;
        }
        List<String> roleIds = roles.stream().map(AppRoleEntity::roleId).toList();
        return appRoleMapper.listPermissionCodesByRoles(roleIds).contains(MANAGE_PERMISSION);
    }

    private AccountResponse toResponse(AppUserEntity user) {
        List<RoleBriefResponse> roles = appUserMapper.listRolesByUser(user.userId()).stream()
                .map(role -> new RoleBriefResponse(role.roleId(), role.name()))
                .toList();
        return new AccountResponse(user.userId(), user.username(), user.displayName(),
                roles, user.status(), user.createTime());
    }

    private AppUserEntity requireUser(String userId) {
        AppUserEntity user = appUserMapper.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账户不存在");
        }
        return user;
    }

    private List<AppRoleEntity> requireRoles(List<String> roleIds) {
        List<String> distinctRoleIds = roleIds == null ? List.of() : roleIds.stream()
                .map(roleId -> roleId == null ? null : roleId.trim())
                .filter(roleId -> roleId != null && !roleId.isEmpty())
                .distinct()
                .toList();
        if (distinctRoleIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "账户至少需要指定一个角色");
        }
        List<AppRoleEntity> roles = appRoleMapper.listByIds(distinctRoleIds);
        if (roles.size() != distinctRoleIds.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "存在无效的角色标识");
        }
        return roles;
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        String trimmed = username.trim();
        if (!trimmed.matches(USERNAME_PATTERN)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "用户名需为 3-32 位字母、数字、下划线或连字符");
        }
        return trimmed;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "显示名不能为空");
        }
        String trimmed = displayName.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > DISPLAY_NAME_MAX_CODE_POINTS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "显示名不能超过 " + DISPLAY_NAME_MAX_CODE_POINTS + " 个字符");
        }
        return trimmed;
    }

    private static AccountStatus parseStatus(String status) {
        for (AccountStatus value : AccountStatus.values()) {
            if (value.code().equals(status)) {
                return value;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "账户状态不合法");
    }
}
