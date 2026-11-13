package com.lender.assistant.account.model.entity;

/**
 * app_user 行对象。password_hash 只在登录校验与自举时使用，永不返回前端。
 */
public record AppUserEntity(String userId, String username, String displayName,
                            String passwordHash, String status,
                            String createTime, String updateTime) {
}
