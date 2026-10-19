package com.lender.assistant.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * BizException 单元测试：错误码必须原样保留（全局处理器据此定 HTTP 状态与对外 code），
 * 文案则遵循"自定义优先、缺省回落到 ErrorCode 默认值"的契约。
 */
class BizExceptionTest {

    @Test
    @DisplayName("单参构造：message 回落到 ErrorCode 默认文案")
    void fallsBackToDefaultMessage() {
        BizException e = new BizException(ErrorCode.NOT_FOUND);

        assertSame(ErrorCode.NOT_FOUND, e.errorCode(), "错误码须原样保留");
        assertEquals("资源不存在", e.getMessage(), "未传自定义文案时用 ErrorCode 默认值");
    }

    @Test
    @DisplayName("双参构造：message 用自定义文案覆盖默认值")
    void customMessageOverridesDefault() {
        BizException e = new BizException(ErrorCode.PARAM_INVALID, "docId 必填");

        assertSame(ErrorCode.PARAM_INVALID, e.errorCode(), "错误码须原样保留");
        assertEquals("docId 必填", e.getMessage(), "自定义文案优先于默认值");
    }
}
