package com.lender.assistant.account.controller;

import com.lender.assistant.account.model.request.LoginRequest;
import com.lender.assistant.account.model.response.AuthAccountResponse;
import com.lender.assistant.account.model.response.LoginResponse;
import com.lender.assistant.account.model.response.LogoutResponse;
import com.lender.assistant.account.service.AuthService;
import com.lender.assistant.common.constant.AuthConstants;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口。除登录外均由 AuthInterceptor 校验令牌后写入请求属性。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录匿名；报文空白属格式问题（400），凭据不符才是 401 */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "口令不能为空");
        }
        return authService.login(request.username().trim(), request.password());
    }

    /** 登出只失效当前令牌，其他设备会话保持可用 */
    @PostMapping("/logout")
    public LogoutResponse logout(
            @RequestAttribute(AuthConstants.TOKEN_HASH_ATTRIBUTE) String tokenHash) {
        authService.logout(tokenHash);
        return new LogoutResponse();
    }

    /** 当前账户：返回角色与权限点，供前端过滤菜单与按钮 */
    @GetMapping("/me")
    public AuthAccountResponse me(
            @RequestAttribute(AuthConstants.USER_ID_ATTRIBUTE) String userId) {
        return authService.currentAccount(userId);
    }
}
