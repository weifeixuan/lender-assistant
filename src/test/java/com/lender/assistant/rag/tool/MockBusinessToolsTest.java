package com.lender.assistant.rag.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.tool.impl.MockCustomerProfileTool;
import com.lender.assistant.rag.tool.impl.MockLenderProductTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两个 mock 业务工具：参数校验、数据命中、无 chunkIds。数据来自 resources/mock 下的固定 JSON。 */
class MockBusinessToolsTest {

    private static final ToolContext CONTEXT = new ToolContext(4);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockCustomerProfileTool customerTool = new MockCustomerProfileTool(mapper);
    private final MockLenderProductTool productTool = new MockLenderProductTool(mapper);

    @Test
    @DisplayName("客户号命中返回额度与风险摘要，且不产生来源")
    void customerProfileReturnsSummary() {
        ToolResult result = customerTool.call(Map.of("customerId", "C10001"), CONTEXT);

        assertTrue(result.ok());
        assertTrue(result.content().contains("授信"));
        assertTrue(result.content().contains("可用"));
        assertTrue(result.chunkIds().isEmpty());
    }

    @Test
    @DisplayName("客户号格式不合法判 ARGS_INVALID，查不到判 TOOL_FAILED")
    void customerProfileValidatesArgs() {
        assertEquals("ARGS_INVALID", customerTool.call(Map.of(), CONTEXT).errorCode());
        assertEquals("ARGS_INVALID", customerTool.call(Map.of("customerId", "10001"), CONTEXT).errorCode());
        assertEquals("ARGS_INVALID", customerTool.call(Map.of("customerId", "C1000"), CONTEXT).errorCode());
        assertEquals("TOOL_FAILED", customerTool.call(Map.of("customerId", "C99999"), CONTEXT).errorCode());
    }

    @Test
    @DisplayName("产品编码命中返回费率与期限摘要")
    void lenderProductReturnsSummary() {
        ToolResult result = productTool.call(Map.of("productCode", "P001"), CONTEXT);

        assertTrue(result.ok());
        assertTrue(result.content().contains("费率"));
        assertTrue(result.chunkIds().isEmpty());
        assertFalse(result.content().isBlank());
    }

    @Test
    @DisplayName("产品编码缺失或查不到时按错误码返回")
    void lenderProductValidatesArgs() {
        assertEquals("ARGS_INVALID", productTool.call(Map.of(), CONTEXT).errorCode());
        assertEquals("ARGS_INVALID", productTool.call(Map.of("productCode", " "), CONTEXT).errorCode());
        assertEquals("TOOL_FAILED", productTool.call(Map.of("productCode", "P999"), CONTEXT).errorCode());
    }
}
