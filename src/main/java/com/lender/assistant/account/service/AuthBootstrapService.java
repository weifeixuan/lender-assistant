package com.lender.assistant.account.service;

/**
 * 启动引导：确保内置管理员角色与初始管理员存在，幂等可重复执行。
 */
public interface AuthBootstrapService {

    /**
     * 缺口令时只确保内置角色并记录一条警告；已存在的账户不覆盖口令，只补角色绑定。
     *
     * @return 初始管理员账户标识；缺口令导致未建号时返回 null
     */
    String bootstrap();
}
