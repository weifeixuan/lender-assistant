package com.lender.assistant.account.service;

import com.lender.assistant.account.model.request.ChangePasswordRequest;
import com.lender.assistant.account.model.request.CreateAccountRequest;
import com.lender.assistant.account.model.request.UpdateAccountRequest;
import com.lender.assistant.account.model.response.AccountResponse;

import java.util.List;

/**
 * 账户管理：列表、新建、改资料与角色、口令、状态。
 *
 * 五条硬约束在服务层强制生效（票 04）：不能禁用自己；不能禁用或降权最后一个
 * 具备账户管理能力的账户；口令字节上限；角色集合非空；用户名不区分大小写唯一。
 */
public interface AccountService {

    /** 列表按创建时间升序 */
    List<AccountResponse> listAccounts();

    /** 新建账户，必须指定至少一个角色 */
    AccountResponse createAccount(CreateAccountRequest request);

    /** 改显示名与角色集合，返回更新后的条目 */
    AccountResponse updateAccount(String userId, UpdateAccountRequest request);

    /** 管理员重置他人口令：失效该账户全部会话 */
    void resetPassword(String userId, String newPassword);

    /** 修改自己的口令：校验旧口令，只失效其他设备会话 */
    void changeOwnPassword(String userId, String currentTokenHash, ChangePasswordRequest request);

    /** 启用/禁用账户：禁用即失效全部会话，并按操作后的系统状态校验最后一个管理员 */
    AccountResponse updateStatus(String operatorUserId, String userId, String status);
}
