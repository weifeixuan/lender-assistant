package com.lender.assistant.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ErrorCode 单元测试：code 是前端分支的唯一依据，一旦漂移整套错误契约都会错位，故锁死。
 * 预期值来自 HTTP 语义（400/404/500）与约定的业务码段（1001），非由实现推导。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("SUCCESS：code=200 且默认文案为 ok")
    void successCodeAndMessage() {
        assertEquals(200, ErrorCode.SUCCESS.code(), "成功码固定 200，前端据此判成功");
        assertEquals("ok", ErrorCode.SUCCESS.defaultMessage(), "成功默认文案固定 ok");
    }

    @Test
    @DisplayName("PARAM_INVALID / NOT_FOUND / INTERNAL_ERROR：code 与 HTTP 语义对齐")
    void httpAlignedCodes() {
        assertEquals(400, ErrorCode.PARAM_INVALID.code(), "参数校验失败对应 400");
        assertEquals(404, ErrorCode.NOT_FOUND.code(), "资源不存在对应 404");
        assertEquals(500, ErrorCode.INTERNAL_ERROR.code(), "系统错误对应 500");
    }

    @Test
    @DisplayName("LLM_FAILED：业务码 1001，不挤占 HTTP 状态码空间")
    void llmFailedUsesBusinessCode() {
        assertEquals(1001, ErrorCode.LLM_FAILED.code(), "模型调用失败是业务码 1001");
        assertEquals("模型调用失败", ErrorCode.LLM_FAILED.defaultMessage(), "默认文案须可读");
    }
}
