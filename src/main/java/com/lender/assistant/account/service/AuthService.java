package com.lender.assistant.account.service;

import com.lender.assistant.account.model.dto.AuthenticatedAccountDTO;
import com.lender.assistant.account.model.response.AuthAccountResponse;
import com.lender.assistant.account.model.response.LoginResponse;

/**
 * 认证与账户上下文服务：登录、登出、当前账户、每请求鉴权。
 */
public interface AuthService {

    /** 校验凭据并签发不透明令牌；三类凭据错误统一 401 同一文案 */
    LoginResponse login(String username, String password);

    /** 只失效当前令牌对应会话，多设备互不影响 */
    void logout(String tokenHash);

    /** 当前登录账户（角色与权限点并集） */
    AuthAccountResponse currentAccount(String userId);

    /**
     * 令牌校验：摘要查表 → 未过期 → 账户启用。任何一步失败统一 401。
     * 权限点每次请求从库里重算，角色调整下一次请求即生效。
     */
    AuthenticatedAccountDTO authenticate(String token);
}
