package com.lender.assistant.account.model.response;

/**
 * 登录成功出参：令牌只在这一次响应里出现，前端自行保存。
 */
public record LoginResponse(String token, String expireTime, AuthAccountResponse user) {
}
