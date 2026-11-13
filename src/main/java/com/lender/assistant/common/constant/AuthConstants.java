package com.lender.assistant.common.constant;

/**
 * 认证相关字面量：请求属性名、令牌头格式、内置角色与对外文案。
 *
 * 请求属性名沿用 {@code userId}：业务层取值方式不变，替换的是写入来源
 * （旧的可伪造请求头 → 服务端会话）。
 */
public final class AuthConstants {

    /** 认证上下文写入的账户标识属性名（controller 以 @RequestAttribute 取用） */
    public static final String USER_ID_ATTRIBUTE = "userId";

    /** 当前请求令牌的 SHA-256 摘要属性名：登出按它精确定位本次会话 */
    public static final String TOKEN_HASH_ATTRIBUTE = "authTokenHash";

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /** 内置角色：不可删除/改名/改权限，代码短路为拥有全部权限点 */
    public static final String BUILTIN_ADMIN_ROLE_NAME = "系统管理员";

    /** 初始管理员的默认显示名 */
    public static final String BOOTSTRAP_ADMIN_DISPLAY_NAME = "系统管理员";

    /** 登录凭据错误统一文案：不区分账户不存在、口令错误与已禁用 */
    public static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    /** 受保护接口登录态失效统一文案 */
    public static final String SESSION_INVALID_MESSAGE = "登录已失效，请重新登录";

    /** 已登录但缺权限点 */
    public static final String FORBIDDEN_MESSAGE = "无访问权限";

    /** 令牌随机字节数（SecureRandom） */
    public static final int TOKEN_BYTES = 32;

    /** 会话默认有效期（天） */
    public static final int DEFAULT_SESSION_TTL_DAYS = 7;

    private AuthConstants() {
    }
}
