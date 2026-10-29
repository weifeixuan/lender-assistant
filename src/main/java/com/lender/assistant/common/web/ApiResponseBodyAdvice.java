package com.lender.assistant.common.web;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.dto.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一响应包装：把 @RestController 的普通返回值包成 {@link ApiResponse}(code/msg/data)。
 *
 * 为什么回到 ResponseBodyAdvice（回退 ADR-0007）：切面 @Around 返回 ApiResponse 时，
 * controller 被 CGLIB 代理后，生成的代理方法会把返回值强制转换回声明类型
 * （如 List&lt;DocSummary&gt;），导致 ClassCastException——返回 List 的接口全部 500。
 * ResponseBodyAdvice 在消息转换器出站前替换 body，不改变 JVM 方法返回值，天然没有这个问题。
 *
 * 不包装的情形：
 * - SseEmitter：SSE 流式协议不能被包装；
 * - ResponseEntity：状态码/响应头自带语义，包装会把整体塞进 data；
 * - String：StringHttpMessageConverter 会因 body 变成 ApiResponse 而类型不匹配；
 * - 已是 ApiResponse：异常处理器等场景手工产出，二次包装会变成 code 套 code；
 * - 被 @RawResponse（方法级或类级）显式标记的接口，如 /health 探针。
 */
@RestControllerAdvice
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        if (SseEmitter.class.isAssignableFrom(type)
                || ResponseEntity.class.isAssignableFrom(type)
                || String.class.isAssignableFrom(type)) {
            return false;
        }
        return !returnType.hasMethodAnnotation(RawResponse.class)
                && !returnType.getContainingClass().isAnnotationPresent(RawResponse.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse) {
            return body;
        }
        return ApiResponse.ok(body);
    }
}
