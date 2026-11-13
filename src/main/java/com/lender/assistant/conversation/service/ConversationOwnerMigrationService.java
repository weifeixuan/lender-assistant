package com.lender.assistant.conversation.service;

/**
 * 历史会话归属迁移：把升级前挂在调试身份下的会话交给初始管理员。
 *
 * 只在启动自举阶段调用一次；幂等由 SQL 的来源账户条件保证，重复执行不产生额外变更。
 */
public interface ConversationOwnerMigrationService {

    /**
     * 迁移历史调试身份下的全部会话（含已删除）到初始管理员。
     *
     * @param bootstrapAdminUserId 初始管理员账户标识；null 或空白表示建号被跳过
     * @return 实际迁移的会话行数；跳过时为 0
     */
    int migrateLegacyOwner(String bootstrapAdminUserId);
}
