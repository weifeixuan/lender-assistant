package com.lender.assistant.common.dto;

import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ApiResponse 单元测试：code/msg/data 是前后端唯一约定的响应契约（AGENTS.md §5.8），
 * 字段语义一旦漂移，前端所有分支判断都会错位，故逐字段锁死。
 */
class ApiResponseTest {

    @Test
    @DisplayName("ok：code=200、msg=ok，data 原样透传")
    void okWrapsData() {
        Map<String, Object> payload = Map.of("docId", "huaxin_corp_api_v1");

        ApiResponse<Map<String, Object>> resp = ApiResponse.ok(payload);

        assertEquals(200, resp.code(), "成功码固定 200");
        assertEquals("ok", resp.msg(), "成功文案固定 ok");
        assertSame(payload, resp.data(), "data 须原样透传，不得二次包装");
    }

    @Test
    @DisplayName("ok：data 允许为 null（只关心成功信号的写操作）")
    void okAllowsNullData() {
        ApiResponse<Void> resp = ApiResponse.ok(null);

        assertEquals(200, resp.code(), "空 data 也仍是成功码 200");
        assertNull(resp.data(), "无内容时 data 为 null");
    }

    @Test
    @DisplayName("fail：code 取自 ErrorCode、msg 用自定义文案，data 恒为 null")
    void failCarriesErrorCodeAndNullsData() {
        ApiResponse<Void> resp = ApiResponse.fail(ErrorCode.PARAM_INVALID, "topK 必须为正整数");

        assertEquals(400, resp.code(), "失败码取自 ErrorCode");
        assertEquals("topK 必须为正整数", resp.msg(), "自定义文案须原样保留");
        assertNull(resp.data(), "失败响应不带 data");
    }
}
