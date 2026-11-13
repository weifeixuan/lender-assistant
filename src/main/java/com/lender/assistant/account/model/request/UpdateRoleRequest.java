package com.lender.assistant.account.model.request;

import java.util.List;

/**
 * 编辑自定义角色报文。权限点整体替换；内置角色一律拒绝。
 */
public record UpdateRoleRequest(String name, String description, List<String> permissionCodes) {
}
