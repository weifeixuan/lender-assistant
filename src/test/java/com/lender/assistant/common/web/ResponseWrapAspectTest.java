package com.lender.assistant.common.web;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.dto.ApiResponse;
import com.lender.assistant.common.exception.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ResponseWrapAspect 单元测试：统一响应包装的选路与幂等是本次改造最容易出错的点——
 * 包错 SSE 会破坏流式协议，漏判已包装响应会产出嵌套结构，包错 ResponseEntity 会破坏 HTTP 语义。
 * 另含顺序锁定用例：两个切面的 @Order 配错不会报错，只会让出参日志悄悄变回原始值，必须用测试钉死。
 */
class ResponseWrapAspectTest {

    private final ResponseWrapAspect aspect = new ResponseWrapAspect();

    /** 测试替身：覆盖各种需要/不需要包装的返回类型，不参与生产装配 */
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
    }

    @RawResponse
    static class RawAnnotatedController {
        public Map<String, Object> normal() {
            return Map.of();
        }
    }

    /** 构造一个"controller 返回 result"的切点替身，方法签名取自测试替身类的真实方法 */
    private static ProceedingJoinPoint joinPointOf(Class<?> type, String methodName, Object result) throws Throwable {
        Method method = type.getMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(type);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    @Test
    @DisplayName("wrap：普通返回值包成 code=200、msg=ok 的统一体")
    void wrapsPlainBody() throws Throwable {
        Object wrapped = aspect.wrap(joinPointOf(SampleController.class, "normal", Map.of("docId", "x")));

        assertInstanceOf(ApiResponse.class, wrapped, "普通返回值应被包成 ApiResponse");
        ApiResponse<?> resp = (ApiResponse<?>) wrapped;
        assertEquals(200, resp.code(), "成功码固定 200");
        assertEquals("ok", resp.msg(), "成功文案固定 ok");
        assertNotNull(resp.data(), "data 应携带原始返回值");
    }

    @Test
    @DisplayName("wrap：已是 ApiResponse（异常处理器产出）时幂等，不二次包装")
    void keepsApiResponseAsIs() throws Throwable {
        ApiResponse<Void> failure = ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数校验失败");

        Object wrapped = aspect.wrap(joinPointOf(SampleController.class, "normal", failure));

        assertSame(failure, wrapped, "已包装响应必须原样返回，否则会变成 code 套 code 的嵌套结构");
    }

    @Test
    @DisplayName("wrap：SseEmitter 不包装")
    void keepsSseAsIs() throws Throwable {
        SseEmitter emitter = new SseEmitter();

        Object wrapped = aspect.wrap(joinPointOf(SampleController.class, "sse", emitter));

        assertSame(emitter, wrapped, "SseEmitter 是流式响应，包装会破坏 SSE 事件协议");
    }

    @Test
    @DisplayName("wrap：ResponseEntity 不包装")
    void keepsResponseEntityAsIs() throws Throwable {
        ResponseEntity<Map<String, Object>> entity = ResponseEntity.ok(Map.of());

        Object wrapped = aspect.wrap(joinPointOf(SampleController.class, "entity", entity));

        assertSame(entity, wrapped, "ResponseEntity 自带状态与头语义，整体包装会把它塞进 data");
    }

    @Test
    @DisplayName("wrap：方法级 @RawResponse 不包装")
    void keepsMethodAnnotatedRaw() throws Throwable {
        Map<String, Object> raw = Map.of();

        Object wrapped = aspect.wrap(joinPointOf(SampleController.class, "rawByMethod", raw));

        assertSame(raw, wrapped, "@RawResponse 显式声明原始响应，跳过包装");
    }

    @Test
    @DisplayName("wrap：类级 @RawResponse 使其所有方法都不包装")
    void keepsClassAnnotatedRaw() throws Throwable {
        Map<String, Object> raw = Map.of();

        Object wrapped = aspect.wrap(joinPointOf(RawAnnotatedController.class, "normal", raw));

        assertSame(raw, wrapped, "类级 @RawResponse 覆盖该类全部方法");
    }

    @Test
    @DisplayName("wrap：null 返回值包成 data=null 的成功体（与原 Advice 行为对齐）")
    void wrapsNullBody() throws Throwable {
        Object wrapped = aspect.wrap(joinPointOf(SampleController.class, "normal", null));

        assertInstanceOf(ApiResponse.class, wrapped, "null 也应包成统一体，行为与原 ResponseBodyAdvice 对齐");
        assertEquals(200, ((ApiResponse<?>) wrapped).code());
    }

    @Test
    @DisplayName("顺序锁定：日志切面必须在外层(@Order=1)，包装切面在内层(@Order=2)")
    void orderContractLocked() {
        Order logOrder = ControllerLogAspect.class.getAnnotation(Order.class);
        Order wrapOrder = ResponseWrapAspect.class.getAnnotation(Order.class);

        assertNotNull(logOrder, "ControllerLogAspect 必须显式声明 @Order，否则切面顺序未定义");
        assertNotNull(wrapOrder, "ResponseWrapAspect 必须显式声明 @Order，否则切面顺序未定义");
        assertTrue(logOrder.value() < wrapOrder.value(),
                "值小者在外层：日志必须包住包装，proceed() 才能拿到包装后的出参");
    }
}
