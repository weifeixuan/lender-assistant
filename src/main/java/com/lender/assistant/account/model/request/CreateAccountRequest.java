package com.lender.assistant.account.model.request;

import java.util.List;

/**
 * 新建账户报文。roleIds 至少一个；口令规则见 {@code PasswordPolicy}。
 */
public record CreateAccountRequest(String username, String displayName, String password,
                                   List<String> roleIds) {
}
