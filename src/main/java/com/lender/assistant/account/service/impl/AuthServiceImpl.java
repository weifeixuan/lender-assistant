package com.lender.assistant.account.service.impl;

import com.lender.assistant.account.mapper.AppUserMapper;
import com.lender.assistant.account.mapper.UserSessionMapper;
import com.lender.assistant.account.model.dto.AuthenticatedAccountDTO;
import com.lender.assistant.account.model.dto.RoleBriefDTO;
import com.lender.assistant.account.model.entity.AppRoleEntity;
import com.lender.assistant.account.model.entity.AppUserEntity;
import com.lender.assistant.account.model.entity.UserSessionEntity;
import com.lender.assistant.account.model.response.AuthAccountResponse;
import com.lender.assistant.account.model.response.LoginResponse;
import com.lender.assistant.account.model.response.RoleBriefResponse;
import com.lender.assistant.account.service.AuthService;
import com.lender.assistant.account.model.enums.AccountStatus;
import com.lender.assistant.common.constant.AuthConstants;
import com.lender.assistant.account.model.enums.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * 认证实现：opaque token + 服务端会话。
 *
 * 取舍见 ADR-0019：库中只存令牌 SHA-256 摘要；有效期固定不滑动；
 * 权限每次请求重算，内置管理员角色短路为全部权限点。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserMapper appUserMapper;
    private final UserSessionMapper userSessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final int sessionTtlDays;

    public AuthServiceImpl(AppUserMapper appUserMapper,
                           UserSessionMapper userSessionMapper,
                           PasswordEncoder passwordEncoder,
                           @Value("${auth.session-ttl-days:7}") int sessionTtlDays) {
        this.appUserMapper = appUserMapper;
        this.userSessionMapper = userSessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionTtlDays = sessionTtlDays;
    }

    @Override
    @Transactional
    public LoginResponse login(String username, String password) {
        AppUserEntity user = appUserMapper.findByUsernameIgnoreCase(username);
        if (user == null
                || !AccountStatus.ENABLED.code().equals(user.status())
                || !passwordEncoder.matches(password, user.passwordHash())) {
            // 三种失败不给可区分文案：避免通过提示差异反推账户是否存在
            throw new BizException(ErrorCode.UNAUTHORIZED, AuthConstants.LOGIN_FAILED_MESSAGE);
        }

        String token = newToken();
        LocalDateTime expireAt = LocalDateTime.now().plusDays(sessionTtlDays);
        // 登录成功顺手清理该账户过期会话，不引入定时任务
        userSessionMapper.deleteExpiredByUser(user.userId());
        userSessionMapper.insert(sha256Hex(token), user.userId(), expireAt);

        AuthenticatedAccountDTO account = buildAccount(
                user.userId(), user.username(), user.displayName(), null);
        return new LoginResponse(token, expireAt.format(TIME_FORMAT), toResponse(account));
    }

    @Override
    public void logout(String tokenHash) {
        userSessionMapper.deleteByTokenHash(tokenHash);
    }

    @Override
    public AuthAccountResponse currentAccount(String userId) {
        AppUserEntity user = appUserMapper.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, AuthConstants.SESSION_INVALID_MESSAGE);
        }
        return toResponse(buildAccount(user.userId(), user.username(), user.displayName(), null));
    }

    @Override
    public AuthenticatedAccountDTO authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw invalidSession();
        }
        String tokenHash = sha256Hex(token);
        UserSessionEntity session = userSessionMapper.findByTokenHash(tokenHash);
        if (session == null || !AccountStatus.ENABLED.code().equals(session.status())) {
            // 过期由 SQL 过滤，这里与「令牌不存在」「账户禁用」同归 401，文案不区分
            throw invalidSession();
        }
        return buildAccount(session.userId(), session.username(), session.displayName(), tokenHash);
    }

    /**
     * 组装账户上下文。角色与权限点每次从库里读取：不做进程内缓存，
     * 角色权限调整后下一次请求即按新权限判定。
     */
    private AuthenticatedAccountDTO buildAccount(String userId, String username,
                                                 String displayName, String tokenHash) {
        List<AppRoleEntity> roles = appUserMapper.listRolesByUser(userId);
        return new AuthenticatedAccountDTO(userId, username, displayName, tokenHash,
                roles.stream().map(role -> new RoleBriefDTO(role.roleId(), role.name())).toList(),
                resolvePermissions(userId, roles));
    }

    /**
     * 权限取并集；任一角色为内置管理员则短路为全部权限点——
     * 新增权限点自动生效，不会因为忘记补关联表把管理员锁在门外。
     * 库里出现未识别的权限码属于数据完整性问题，显式失败而不是静默忽略。
     */
    private List<String> resolvePermissions(String userId, List<AppRoleEntity> roles) {
        boolean builtinAdmin = roles.stream().anyMatch(role -> Boolean.TRUE.equals(role.builtin()));
        if (builtinAdmin) {
            return Arrays.stream(PermissionCode.values()).map(PermissionCode::code).toList();
        }
        try {
            return appUserMapper.listPermissionCodesByUser(userId).stream()
                    .map(PermissionCode::fromCode)
                    .map(PermissionCode::code)
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("角色权限表存在未识别的权限点", e);
        }
    }

    private AuthAccountResponse toResponse(AuthenticatedAccountDTO account) {
        return new AuthAccountResponse(account.userId(), account.username(), account.displayName(),
                account.roles().stream()
                        .map(role -> new RoleBriefResponse(role.roleId(), role.name()))
                        .toList(),
                account.permissions());
    }

    private static BizException invalidSession() {
        return new BizException(ErrorCode.UNAUTHORIZED, AuthConstants.SESSION_INVALID_MESSAGE);
    }

    /** 32 字节 SecureRandom，Base64URL 无填充：令牌只在这一步以原文存在 */
    private static String newToken() {
        byte[] bytes = new byte[AuthConstants.TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
