package com.lender.assistant.rag.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.context.AgentToolContextBuilder;
import com.lender.assistant.rag.context.ToolContextContextBuilder;
import com.lender.assistant.rag.context.ToolErrorCodeContextBuilder;
import com.lender.assistant.rag.context.ToolResultContextBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * customer.profile（mock）：按客户号返回授信与风险摘要。
 *
 * 本地固定数据、无网络请求、无密钥；chunkIds 恒为空，不参与 sources 并集。
 * 默认关闭，只有 rag.plan.tools.mock-enabled=true 时才注册进 PlanToolRegistryContextBuilder。
 */
@Component
@ConditionalOnProperty(name = "rag.plan.tools.mock-enabled", havingValue = "true")
public class MockCustomerProfileToolContextBuilder implements AgentToolContextBuilder {

    private static final String NAME = "customer.profile";
    private static final Pattern CUSTOMER_ID = Pattern.compile("C\\d{5}");
    private static final String DATA_PATH = "/mock/customer-profiles.json";

    private final JsonNode profiles;

    public MockCustomerProfileToolContextBuilder(ObjectMapper objectMapper) {
        this.profiles = load(objectMapper);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResultContextBuilder call(Map<String, Object> args, ToolContextContextBuilder context) {
        String customerId = args.get("customerId") instanceof String text ? text.trim() : "";
        if (!CUSTOMER_ID.matcher(customerId).matches()) {
            return ToolResultContextBuilder.failure(ToolErrorCodeContextBuilder.ARGS_INVALID, "customerId 必须形如 C10001");
        }
        JsonNode profile = profiles.get(customerId);
        if (profile == null) {
            return ToolResultContextBuilder.failure(ToolErrorCodeContextBuilder.TOOL_FAILED, "未找到客户 " + customerId);
        }
        return ToolResultContextBuilder.success(summarize(customerId, profile), java.util.List.of());
    }

    private static String summarize(String customerId, JsonNode profile) {
        return "客户 " + customerId
                + "：授信 " + profile.path("creditTotal").asText() + " 万元"
                + "；已用 " + profile.path("creditUsed").asText() + " 万元"
                + "；可用 " + profile.path("creditAvailable").asText() + " 万元"
                + "；风险等级 " + profile.path("riskLevel").asText()
                + "；合作状态 " + profile.path("cooperationStatus").asText();
    }

    private JsonNode load(ObjectMapper objectMapper) {
        try (InputStream stream = getClass().getResourceAsStream(DATA_PATH)) {
            return stream == null ? objectMapper.createObjectNode()
                    : objectMapper.readTree(stream);
        } catch (IOException ex) {
            return objectMapper.createObjectNode();
        }
    }
}
