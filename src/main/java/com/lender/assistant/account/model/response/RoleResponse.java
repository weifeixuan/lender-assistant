package com.lender.assistant.account.model.response;

import java.util.List;

/**
 * 角色条目：权限点只给码，页面用权限点目录翻译。
 */
public record RoleResponse(String roleId, String name, String description,
                           List<String> permissionCodes, int accountCount, boolean builtin) {
}
