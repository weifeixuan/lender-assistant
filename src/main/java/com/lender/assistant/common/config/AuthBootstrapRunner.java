package com.lender.assistant.common.config;

import com.lender.assistant.account.service.AuthBootstrapService;
import com.lender.assistant.conversation.service.ConversationOwnerMigrationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动引导入口：在建表完成后、对外提供流量前确保内置角色与初始管理员存在，
 * 再把历史调试身份下的会话归属交给初始管理员。
 * 具体幂等逻辑在各自领域的服务层，这里只负责顺序与触发时机。
 */
@Component
public class AuthBootstrapRunner implements ApplicationRunner {

    private final AuthBootstrapService authBootstrapService;
    private final ConversationOwnerMigrationService conversationOwnerMigrationService;

    public AuthBootstrapRunner(AuthBootstrapService authBootstrapService,
                               ConversationOwnerMigrationService conversationOwnerMigrationService) {
        this.authBootstrapService = authBootstrapService;
        this.conversationOwnerMigrationService = conversationOwnerMigrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String adminUserId = authBootstrapService.bootstrap();
        conversationOwnerMigrationService.migrateLegacyOwner(adminUserId);
    }
}
