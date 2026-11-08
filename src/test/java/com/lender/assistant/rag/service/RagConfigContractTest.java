package com.lender.assistant.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 配置契约：代码侧去掉 @Value 默认值后，启动所需的每一项都必须由 application.yml 提供。
 */
class RagConfigContractTest {

    @Test
    @DisplayName("已移除代码默认值的配置项必须由 application.yml 提供")
    void applicationYmlProvidesKeysWithoutCodeDefaults() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        PropertySource<?> source = sources.get(0);

        for (String key : List.of(
                "rag.routing.irrelevant-reply",
                "rag.routing.confidence-threshold",
                "rag.plan.total-budget-ms",
                "rag.plan.min-steps",
                "rag.plan.max-steps",
                "rag.search.max-distance")) {
            assertNotNull(source.getProperty(key),
                    "缺少配置项 " + key + "：代码侧已移除默认值，缺失会导致启动失败");
        }
    }
}
