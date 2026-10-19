package com.lender.assistant.common.web;

import com.lender.assistant.common.dto.ApiResponse;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GlobalExceptionHandler 单元测试：错误响应的 code/msg 契约与"不泄露内部信息"红线（§5.8）在此锁死。
 * 预期值取自 ErrorCode 定义与 HTTP 语义，不由实现推导。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BizException：HTTP 状态取自 ErrorCode，body 为 code/msg 且无 data")
    void handlesBizException() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBizException(new BizException(ErrorCode.NOT_FOUND, "文档不存在"));

        assertEquals(404, resp.getStatusCode().value(), "NOT_FOUND 对应 HTTP 404");
        assertNotNull(resp.getBody(), "错误响应必须有 body");
        assertEquals(404, resp.getBody().code(), "body code 与 ErrorCode 一致");
        assertEquals("文档不存在", resp.getBody().msg(), "自定义文案须原样透出");
        assertNull(resp.getBody().data(), "失败响应不带 data");
    }

    @Test
    @DisplayName("BizException 业务码 1001：HTTP 回落 500，body 仍保留业务码供前端分支")
    void businessCodeFallsBackTo500() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBizException(new BizException(ErrorCode.LLM_FAILED));

        assertEquals(500, resp.getStatusCode().value(), "1001 非合法 HTTP 状态码，HTTP 侧回落 500");
        assertEquals(1001, resp.getBody().code(), "body 保留业务码 1001");
        assertEquals("模型调用失败", resp.getBody().msg(), "缺省文案取自 ErrorCode");
    }

    @Test
    @DisplayName("IllegalArgumentException：映射为 400 + 参数校验失败")
    void handlesIllegalArgument() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleIllegalArgument(new IllegalArgumentException("topK 必须为正整数"));

        assertEquals(400, resp.getStatusCode().value(), "入参非法是客户端错误，落 400");
        assertEquals(400, resp.getBody().code(), "code 取 PARAM_INVALID");
        assertEquals("topK 必须为正整数", resp.getBody().msg(), "校验文案须可读");
    }

    @Test
    @DisplayName("未知异常：HTTP 500 + 通用文案，不泄露异常 message 与内部细节")
    void handlesUnexpectedWithoutLeakingInternals() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnexpectedException(
                new IllegalStateException("内部 SQL: select * from bank_card where id=1"));

        assertEquals(500, resp.getStatusCode().value(), "未预期异常落 500");
        assertEquals(500, resp.getBody().code(), "code 取 INTERNAL_ERROR");
        assertEquals("系统错误", resp.getBody().msg(), "对外只给通用文案");
        assertFalse(resp.getBody().msg().contains("bank_card"), "不得把异常 message/SQL 泄漏给调用方");
        assertNull(resp.getBody().data());
    }

    @Test
    @DisplayName("未匹配路由（NoResourceFoundException）：映射为 404 + 资源不存在，而非 500")
    void handlesMissingRouteAsNotFound() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "api/nope"));

        assertEquals(404, resp.getStatusCode().value(), "路由不存在是客户端错误，落 404 而非 500");
        assertEquals(404, resp.getBody().code(), "code 取 NOT_FOUND");
        assertEquals("资源不存在", resp.getBody().msg(), "对外只给通用文案");
        assertNull(resp.getBody().data(), "失败响应不带 data");
    }

    @Test
    @DisplayName("必填参数缺失（MissingServletRequestParameterException）：映射为 400 + 参数校验失败")
    void handlesMissingRequiredParamAsBadRequest() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleClientRequestError(
                new MissingServletRequestParameterException("docId", "String"));

        assertEquals(400, resp.getStatusCode().value(), "报文/参数不合法是客户端错误，落 400 而非 500");
        assertEquals(400, resp.getBody().code(), "code 取 PARAM_INVALID");
        assertEquals("参数校验失败", resp.getBody().msg(), "不回显框架技术性异常信息");
        assertNull(resp.getBody().data());
    }
}
