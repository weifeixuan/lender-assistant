package com.lender.assistant.account.model.response;

import java.util.List;

/**
 * 当前账户出参：角色与权限点用于前端过滤菜单与页面按钮。
 * permissions 为多角色并集；内置管理员短路为全部权限点。
 */
public record AuthAccountResponse(String userId, String username, String displayName,
                                  List<RoleBriefResponse> roles, List<String> permissions) {
}
