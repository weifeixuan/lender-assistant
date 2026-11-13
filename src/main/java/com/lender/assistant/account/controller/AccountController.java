package com.lender.assistant.account.controller;

import com.lender.assistant.account.model.request.ChangePasswordRequest;
import com.lender.assistant.account.model.request.CreateAccountRequest;
import com.lender.assistant.account.model.request.ResetPasswordRequest;
import com.lender.assistant.account.model.request.UpdateAccountRequest;
import com.lender.assistant.account.model.request.UpdateAccountStatusRequest;
import com.lender.assistant.account.model.response.AccountResponse;
import com.lender.assistant.account.service.AccountService;
import com.lender.assistant.common.constant.AuthConstants;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账户管理入口。除「改自己的口令」外均需 {@code settings.accounts.manage}（见 AuthInterceptor）。
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.listAccounts();
    }

    @PostMapping
    public AccountResponse create(@RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    /**
     * 固定路径必须声明在 {@code /{userId}/password} 之前：
     * 避免 {@code me} 被当作账户标识解析。
     */
    @PutMapping("/me/password")
    public void changeOwnPassword(
            @RequestAttribute(AuthConstants.USER_ID_ATTRIBUTE) String userId,
            @RequestAttribute(AuthConstants.TOKEN_HASH_ATTRIBUTE) String tokenHash,
            @RequestBody ChangePasswordRequest request) {
        accountService.changeOwnPassword(userId, tokenHash, request);
    }

    @PutMapping("/{userId}/password")
    public void resetPassword(@PathVariable String userId,
                              @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(userId, request.newPassword());
    }

    @PutMapping("/{userId}/status")
    public AccountResponse updateStatus(
            @RequestAttribute(AuthConstants.USER_ID_ATTRIBUTE) String operatorUserId,
            @PathVariable String userId,
            @RequestBody UpdateAccountStatusRequest request) {
        return accountService.updateStatus(operatorUserId, userId, request.status());
    }

    @PutMapping("/{userId}")
    public AccountResponse update(@PathVariable String userId,
                                  @RequestBody UpdateAccountRequest request) {
        return accountService.updateAccount(userId, request);
    }
}
