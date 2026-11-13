package com.lender.assistant.account.model.dto;

/**
 * 已认证账户的请求级上下文：由认证拦截器在每次请求时从库里重算，
 * 不做进程内缓存，因此角色权限调整下一次请求即生效。
 *
 * tokenHash 只用于定位当前会话（登出），不落日志、不出现在响应体。
 */
public record AuthenticatedAccountDTO(String userId, String username, String displayName,
                                      String tokenHash, java.util.List<RoleBriefDTO> roles,
                                      java.util.List<String> permissions) {
}
