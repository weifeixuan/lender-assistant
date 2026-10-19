package com.lender.assistant.common.web;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.dto.ApiResponse;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiResponseBodyAdvice 单元测试：统一响应包装的选路与幂等是本次改造最容易出错的点——
 * 包错 SSE 会破坏流式协议，漏判已包装响应会产出嵌套结构。故 supports / beforeBodyWrite 各自锁死。
 */
class ApiResponseBodyAdviceTest {

    private final ApiResponseBodyAdvice advice = new ApiResponseBodyAdvice();
    private final Class<? extends HttpMessageConverter<?>> jsonConverter = MappingJackson2HttpMessageConverter.class;

    /** 测试替身：覆盖各种需要/不需要包装的返回类型，不参与生产装配 */
    static class SampleController {
        public Map<String, Object> normal() {
            return Map.of();
        }

        @RawResponse
        public Map<String, Object> rawByMethod() {
            return Map.of();
        }

        public SseEmitter sse() {
            return new SseEmitter();
        }

        public String text() {
            return "raw";
        }
    }

    @RawResponse
    static class RawAnnotatedController {
        public Map<String, Object> normal() {
            return Map.of();
        }
    }

    private static MethodParameter returnOf(Class<?> type, String methodName) throws NoSuchMethodException {
        Method method = type.getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @Test
    @DisplayName("supports：普通 JSON（Map）返回值需要包装")
    void supportsNormalReturn() throws Exception {
        assertTrue(advice.supports(returnOf(SampleController.class, "normal"), jsonConverter),
                "普通 JSON 返回体应被包装");
    }

    @Test
    @DisplayName("supports：SseEmitter 返回体不包装")
    void supportsSkipsSse() throws Exception {
        assertFalse(advice.supports(returnOf(SampleController.class, "sse"), jsonConverter),
                "SseEmitter 是流式响应，包装会破坏 SSE 协议");
    }

    @Test
    @DisplayName("supports：方法级 @RawResponse 不包装")
    void supportsSkipsMethodAnnotated() throws Exception {
        assertFalse(advice.supports(returnOf(SampleController.class, "rawByMethod"), jsonConverter),
                "@RawResponse 显式声明原始响应，跳过包装");
    }

    @Test
    @DisplayName("supports：类级 @RawResponse 使其所有方法都不包装")
    void supportsSkipsClassAnnotated() throws Exception {
        assertFalse(advice.supports(returnOf(RawAnnotatedController.class, "normal"), jsonConverter),
                "类级 @RawResponse 覆盖该类全部方法");
    }

    @Test
    @DisplayName("supports：String 返回值不包装")
    void supportsSkipsPlainString() throws Exception {
        assertFalse(advice.supports(returnOf(SampleController.class, "text"), jsonConverter),
                "String 会走 StringHttpMessageConverter，包成对象将导致转换器类型不匹配");
    }

    @Test
    @DisplayName("beforeBodyWrite：普通 body 包成 code=200、msg=ok 的统一体")
    void wrapsPlainBody() throws Exception {
        Object wrapped = advice.beforeBodyWrite(Map.of("docId", "x"),
                returnOf(SampleController.class, "normal"),
                MediaType.APPLICATION_JSON, jsonConverter, null, null);

        assertInstanceOf(ApiResponse.class, wrapped, "普通返回值应被包成 ApiResponse");
        ApiResponse<?> resp = (ApiResponse<?>) wrapped;
        assertEquals(200, resp.code(), "成功码固定 200");
        assertEquals("ok", resp.msg(), "成功文案固定 ok");
        assertNotNull(resp.data(), "data 应携带原始 body");
    }

    @Test
    @DisplayName("beforeBodyWrite：已是 ApiResponse（异常处理器产出）时幂等，不二次包装")
    void keepsApiResponseAsIs() throws Exception {
        ApiResponse<Void> failure = ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数校验失败");

        Object wrapped = advice.beforeBodyWrite(failure, returnOf(SampleController.class, "normal"),
                MediaType.APPLICATION_JSON, jsonConverter, null, null);

        assertSame(failure, wrapped, "已包装响应必须原样返回，否则会变成 code 套 code 的嵌套结构");
    }
}
