package com.lender.assistant.account.model.request;

/**
 * 管理员重置他人口令报文：不需要旧口令。
 */
public record ResetPasswordRequest(String newPassword) {
}
