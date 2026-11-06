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
import java.util.List;
import java.util.Map;

/**
 * lender.product（mock）：按产品编码返回费率、期限、放款条件与支持的接口类型。
 *
 * 本地固定数据、无网络请求、无密钥；chunkIds 恒为空。
 * 默认关闭，只有 rag.plan.tools.mock-enabled=true 时才注册进 PlanToolRegistryContextBuilder。
 */
@Component
@ConditionalOnProperty(name = "rag.plan.tools.mock-enabled", havingValue = "true")
public class MockLenderProductToolContextBuilder implements AgentToolContextBuilder {

    private static final String NAME = "lender.product";
    private static final String DATA_PATH = "/mock/lender-products.json";

    private final JsonNode products;

    public MockLenderProductToolContextBuilder(ObjectMapper objectMapper) {
        this.products = load(objectMapper);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResultContextBuilder call(Map<String, Object> args, ToolContextContextBuilder context) {
        String productCode = args.get("productCode") instanceof String text ? text.trim() : "";
        if (productCode.isEmpty()) {
            return ToolResultContextBuilder.failure(ToolErrorCodeContextBuilder.ARGS_INVALID, "productCode 必填");
        }
        JsonNode product = products.get(productCode);
        if (product == null) {
            return ToolResultContextBuilder.failure(ToolErrorCodeContextBuilder.TOOL_FAILED, "未找到产品 " + productCode);
        }
        return ToolResultContextBuilder.success(summarize(productCode, product), List.of());
    }

    private static String summarize(String productCode, JsonNode product) {
        return "产品 " + productCode
                + "：费率 " + product.path("rate").asText()
                + "；期限 " + product.path("term").asText()
                + "；放款条件 " + product.path("disburseCondition").asText()
                + "；支持的接口类型 " + product.path("supportedInterfaces").asText();
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
