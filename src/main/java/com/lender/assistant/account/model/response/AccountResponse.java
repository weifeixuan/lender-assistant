package com.lender.assistant.account.model.response;

import java.util.List;

/**
 * 账户条目：不含口令摘要，角色只带标识与名称。
 */
public record AccountResponse(String userId, String username, String displayName,
                              List<RoleBriefResponse> roles, String status, String createTime) {
}
