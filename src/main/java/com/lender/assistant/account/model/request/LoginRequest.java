package com.lender.assistant.account.model.request;

/**
 * 登录报文。字段缺失/空白属于格式问题（400），凭据不符才是 401。
 */
public record LoginRequest(String username, String password) {
}
