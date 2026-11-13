package com.lender.assistant.account.model.response;

/**
 * 账户所属角色摘要（不是完整角色对象，避免把权限点细节带进账户列表/登录响应）。
 */
public record RoleBriefResponse(String roleId, String name) {
}
