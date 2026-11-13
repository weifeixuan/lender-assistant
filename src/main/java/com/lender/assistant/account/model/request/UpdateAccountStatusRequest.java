package com.lender.assistant.account.model.request;

/**
 * 修改账户状态报文：取值为 {@code ENABLED} / {@code DISABLED}。
 */
public record UpdateAccountStatusRequest(String status) {
}
