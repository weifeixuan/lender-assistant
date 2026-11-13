package com.lender.assistant.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期必需配置校验测试：无默认值的密钥缺失时必须启动失败，且报错点名环境变量。
 */
class StartupConfigValidatorTest {

    @Test
    @DisplayName("LLM_API_KEY 为空：启动直接失败，报错指明缺哪个环境变量")
    void blankLlmApiKeyFailsStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupConfigValidator(""));
        assertTrue(e.getMessage().contains("LLM_API_KEY"));
    }

    @Test
    @DisplayName("LLM_API_KEY 已配置：校验通过")
    void configuredLlmApiKeyPasses() {
        assertDoesNotThrow(() -> new StartupConfigValidator("sk-test"));
    }
}
