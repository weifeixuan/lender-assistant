package com.lender.assistant.account.model.entity;

/**
 * app_role 行对象。builtin=true 表示内置角色（系统管理员），权限判定短路为全量权限点。
 */
public record AppRoleEntity(String roleId, String name, String description,
                            Boolean builtin, String createTime, String updateTime) {
}
