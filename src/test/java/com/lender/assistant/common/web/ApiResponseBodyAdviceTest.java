package com.lender.assistant.common.web;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.dto.ApiResponse;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 统一响应包装的选路与幂等测试：包错 SSE 会破坏流式协议，包错 ResponseEntity 会破坏 HTTP 语义，
 * 漏判已包装响应会产出 code 套 code。这些分支出错都只在运行时暴露，必须用测试钉死。
 */
class ApiResponseBodyAdviceTest {

    private final ApiResponseBodyAdvice advice = new ApiResponseBodyAdvice();

    /** 测试替身：覆盖需要/不需要包装的返回类型，不参与生产装配 */
    static class SampleController {
        public Map<String, Object> normal() {
            return Map.of("docId", "x");
        }

        @RawResponse
        public Map<String, Object> rawByMethod() {
            return Map.of();
        }

        public SseEmitter sse() {
            return new SseEmitter();
        }

        public ResponseEntity<Map<String, Object>> entity() {
            return ResponseEntity.ok(Map.of());
        }

        public String text() {
            return "ok";
        }
    }

    @RawResponse
    static class RawAnnotatedController {
        public Map<String, Object> normal() {
            return Map.of();
        }
    }

    /** 用测试替身类的真实方法构造 MethodParameter，注解判定走 Spring 的真实实现而非 mock */
    private static MethodParameter paramOf(Class<?> type, String methodName) throws NoSuchMethodException {
        return new MethodParameter(type.getMethod(methodName), -1);
    }

    @Test
    @DisplayName("supports：普通返回值需要包装")
    void supportsPlainBody() throws NoSuchMethodException {
        assertTrue(advice.supports(paramOf(SampleController.class, "normal"),
                MappingJackson2HttpMessageConverter.class));
    }

    @Test
    @DisplayName("supports：SseEmitter 不包装（会破坏 SSE 事件协议）")
    void rejectsSseEmitter() throws NoSuchMethodException {
        assertFalse(advice.supports(paramOf(SampleController.class, "sse"),
                MappingJackson2HttpMessageConverter.class));
    }

    @Test
    @DisplayName("supports：ResponseEntity 不包装（会丢掉状态码与响应头语义）")
    void rejectsResponseEntity() throws NoSuchMethodException {
        assertFalse(advice.supports(paramOf(SampleController.class, "entity"),
                MappingJackson2HttpMessageConverter.class));
    }

    @Test
    @DisplayName("supports：String 不包装（StringHttpMessageConverter 类型不匹配）")
    void rejectsString() throws NoSuchMethodException {
        assertFalse(advice.supports(paramOf(SampleController.class, "text"),
                MappingJackson2HttpMessageConverter.class));
    }

    @Test
    @DisplayName("supports：方法级 @RawResponse 跳过包装")
    void rejectsMethodAnnotatedRaw() throws NoSuchMethodException {
        assertFalse(advice.supports(paramOf(SampleController.class, "rawByMethod"),
                MappingJackson2HttpMessageConverter.class));
    }

    @Test
    @DisplayName("supports：类级 @RawResponse 使该类所有方法跳过包装")
    void rejectsClassAnnotatedRaw() throws NoSuchMethodException {
        assertFalse(advice.supports(paramOf(RawAnnotatedController.class, "normal"),
                MappingJackson2HttpMessageConverter.class));
    }

    @Test
    @DisplayName("beforeBodyWrite：普通返回值包成 code=200、msg=ok 的统一体")
    void wrapsPlainBody() throws NoSuchMethodException {
        Map<String, Object> body = Map.of("docId", "x");

        Object wrapped = advice.beforeBodyWrite(body, paramOf(SampleController.class, "normal"),
                MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class,
                mock(ServerHttpRequest.class), mock(ServerHttpResponse.class));

        ApiResponse<?> resp = assertInstanceOf(ApiResponse.class, wrapped);
        assertEquals(200, resp.code(), "成功码固定 200");
        assertEquals("ok", resp.msg(), "成功文案固定 ok");
        assertSame(body, resp.data(), "data 必须原样携带 controller 返回值");
    }

    @Test
    @DisplayName("beforeBodyWrite：已是 ApiResponse（异常处理器产出）时幂等")
    void keepsApiResponseAsIs() throws NoSuchMethodException {
        ApiResponse<Void> failure = ApiResponse.fail(ErrorCode.NOT_FOUND, "资源不存在");

        Object wrapped = advice.beforeBodyWrite(failure, paramOf(SampleController.class, "normal"),
                MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class,
                mock(ServerHttpRequest.class), mock(ServerHttpResponse.class));

        assertSame(failure, wrapped, "已包装响应必须原样返回，否则会变成 code 套 code");
    }

    @Test
    @DisplayName("beforeBodyWrite：null 返回值包成 data=null 的成功体")
    void wrapsNullBody() throws NoSuchMethodException {
        Object wrapped = advice.beforeBodyWrite(null, paramOf(SampleController.class, "normal"),
                MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class,
                mock(ServerHttpRequest.class), mock(ServerHttpResponse.class));

        ApiResponse<?> resp = assertInstanceOf(ApiResponse.class, wrapped);
        assertEquals(200, resp.code());
        assertEquals("ok", resp.msg());
    }
}
