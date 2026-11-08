package com.lender.assistant.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 异常文案提取：失败原因必须写得出、看得懂。
 * 关键场景是 WebClientRequestException 这类「包装异常 message 为 null」的情况——
 * 曾经导致 SSE 推「问答失败：null」、llm_call_log 的 error_msg 为空。
 */
class ExceptionMessageUtilsTest {

    @Test
    @DisplayName("自身有 message：原样返回，不加工")
    void keepsOwnMessage() {
        assertEquals("模拟模型 502", ExceptionMessageUtils.describe(new RuntimeException("模拟模型 502")));
    }

    @Test
    @DisplayName("自身 message 为空：取根因 message")
    void fallsBackToRootCauseMessage() {
        RuntimeException wrapper = new RuntimeException((String) null,
                new IllegalStateException("Connection refused: 127.0.0.1:3426"));

        assertEquals("Connection refused: 127.0.0.1:3426", ExceptionMessageUtils.describe(wrapper));
    }

    @Test
    @DisplayName("全链无 message：异常类名兜底，绝不返回空")
    void fallsBackToClassName() {
        assertEquals("RuntimeException", ExceptionMessageUtils.describe(new RuntimeException()));
    }

    @Test
    @DisplayName("空白 message 视同为空：继续向根因回溯")
    void treatsBlankAsEmpty() {
        RuntimeException wrapper = new RuntimeException("   ",
                new IllegalStateException("   "));

        assertEquals("RuntimeException（根因 IllegalStateException）", ExceptionMessageUtils.describe(wrapper));
    }
}
