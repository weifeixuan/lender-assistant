package com.lender.assistant.account.model.request;

import java.util.List;

/**
 * 新建角色报文。permissionCodes 可为空集合；未识别的权限码一律 400。
 */
public record CreateRoleRequest(String name, String description, List<String> permissionCodes) {
}
