package com.lender.assistant.account.model.request;

/**
 * 修改自己的口令报文：必须校验旧口令，成功后保留当前令牌。
 */
public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
