package com.lender.assistant.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动期必需配置校验：缺失时直接失败，并在异常信息里点名需要设置的环境变量。
 *
 * 只校验"无默认值、必须由外部提供"的配置；有默认值的配置不在此列。
 * 向量侧 ZHIPU_API_KEY 仅在 rag.embedding.provider=zhipu 时才必需，
 * 由 ZhipuEmbeddingStrategy 在装配时校验，不放在这里。
 */
@Component
public class StartupConfigValidator {

    public StartupConfigValidator(@Value("${spring.ai.openai.api-key:}") String llmApiKey) {
        if (llmApiKey == null || llmApiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 LLM_API_KEY：对话、路由、计划、抽取、草稿均走真实模型 API，"
                            + "请设置 LLM_API_KEY 后重启服务。");
        }
    }
}
