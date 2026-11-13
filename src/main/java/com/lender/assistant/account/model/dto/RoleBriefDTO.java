package com.lender.assistant.account.model.dto;

/**
 * 账户携带的角色摘要：认证上下文与响应共用，只暴露标识与名称。
 */
public record RoleBriefDTO(String roleId, String name) {
}
