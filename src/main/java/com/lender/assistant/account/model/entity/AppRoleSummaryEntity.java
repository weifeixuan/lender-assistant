package com.lender.assistant.account.model.entity;

/**
 * 角色列表行：角色本体 + 引用账户数（列表页需要「影响多少人」）。
 */
public record AppRoleSummaryEntity(String roleId, String name, String description,
                                   Boolean builtin, String createTime, String updateTime,
                                   int accountCount) {
}
