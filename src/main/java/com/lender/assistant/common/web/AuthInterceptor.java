package com.lender.assistant.common.web;

import com.lender.assistant.account.model.dto.AuthenticatedAccountDTO;
import com.lender.assistant.account.service.AuthService;
import com.lender.assistant.common.constant.AuthConstants;
import com.lender.assistant.common.constant.PermissionCode;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 全局令牌鉴权：替换原来的可伪造身份请求头。
 *
 * 职责两层：
 * 1. 认证——解析 {@code Authorization: Bearer <token>}，校验会话与账户状态，写请求属性；
 * 2. 授权——按「方法 + 路径」表查所需权限点，缺权限 403。
 *
 * 为什么用拦截器而不是 Spring Security：认证失败抛 {@link BizException}，
 * 天然走既有 GlobalExceptionHandler，与 400/404/409 保持同一错误契约；
 * 且失败发生在 controller 之前，流式接口的 401/403 也是普通 JSON。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 方法为 null 表示不区分方法 */
    private record PathPermission(String method, String pattern, PermissionCode permission) {
    }

    /** 有页面入口的接口；未列出的 /api/** 仅要求登录（如抽取、起草、评测） */
    private static final List<PathPermission> RULES = List.of(
            // 改自己的口令只要求登录：必须排在 /api/accounts/** 之前
            new PathPermission("PUT", "/api/accounts/me/password", null),
            new PathPermission(null, "/api/accounts", PermissionCode.SETTINGS_ACCOUNTS_MANAGE),
            new PathPermission(null, "/api/accounts/**", PermissionCode.SETTINGS_ACCOUNTS_MANAGE),
            new PathPermission("GET", "/api/admin/documents", PermissionCode.KNOWLEDGE_DOCUMENTS_VIEW),
            new PathPermission("GET", "/api/admin/documents/**", PermissionCode.KNOWLEDGE_DOCUMENTS_VIEW),
            new PathPermission("POST", "/api/admin/documents/search", PermissionCode.KNOWLEDGE_DOCUMENTS_VIEW),
            new PathPermission("POST", "/api/admin/documents", PermissionCode.KNOWLEDGE_DOCUMENTS_WRITE),
            new PathPermission("DELETE", "/api/admin/documents/**", PermissionCode.KNOWLEDGE_DOCUMENTS_WRITE),
            new PathPermission("POST", "/api/qa", PermissionCode.KNOWLEDGE_CHAT_USE),
            new PathPermission(null, "/api/conversations", PermissionCode.KNOWLEDGE_CHAT_USE),
            new PathPermission(null, "/api/conversations/**", PermissionCode.KNOWLEDGE_CHAT_USE));

    private final AuthService authService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String token = resolveToken(request);
        AuthenticatedAccountDTO account = authService.authenticate(token);
        request.setAttribute(AuthConstants.USER_ID_ATTRIBUTE, account.userId());
        request.setAttribute(AuthConstants.TOKEN_HASH_ATTRIBUTE, account.tokenHash());

        PermissionCode required = requiredPermission(request.getMethod(), request.getRequestURI());
        if (required != null && !account.permissions().contains(required.code())) {
            throw new BizException(ErrorCode.FORBIDDEN, AuthConstants.FORBIDDEN_MESSAGE);
        }
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AuthConstants.AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(AuthConstants.BEARER_PREFIX)) {
            throw invalidSession();
        }
        String token = header.substring(AuthConstants.BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw invalidSession();
        }
        return token;
    }

    private PermissionCode requiredPermission(String method, String uri) {
        for (PathPermission rule : RULES) {
            if (rule.method() != null && !rule.method().equals(method)) {
                continue;
            }
            if (pathMatcher.match(rule.pattern(), uri)) {
                return rule.permission();
            }
        }
        return null;
    }

    private static BizException invalidSession() {
        return new BizException(ErrorCode.UNAUTHORIZED, AuthConstants.SESSION_INVALID_MESSAGE);
    }
}
