package com.lender.assistant.config;

import com.lender.assistant.account.service.AuthBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动引导入口：在建表完成后、对外提供流量前确保内置角色与初始管理员存在。
 * 具体幂等逻辑在服务层，这里只负责触发时机。
 */
@Component
public class AuthBootstrapRunner implements ApplicationRunner {

    private final AuthBootstrapService authBootstrapService;

    public AuthBootstrapRunner(AuthBootstrapService authBootstrapService) {
        this.authBootstrapService = authBootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        authBootstrapService.bootstrap();
    }
}
