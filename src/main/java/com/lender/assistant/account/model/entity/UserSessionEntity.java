package com.lender.assistant.account.model.entity;

/**
 * user_session 行对象（连账户表取状态与展示信息）。
 * token_hash 是令牌的 SHA-256 摘要，库中不存原文。
 */
public record UserSessionEntity(String tokenHash, String userId, String username,
                                String displayName, String status, String expireTime) {
}
