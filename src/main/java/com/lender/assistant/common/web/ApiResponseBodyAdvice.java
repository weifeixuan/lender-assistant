package com.lender.assistant.common.web;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.dto.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 全局统一响应包装：把普通 controller 的 JSON 返回值包成 {@link ApiResponse}(code/msg/data)。
 *
 * 为什么用 ResponseBodyAdvice 而不是 @Around 切面返回值：@Around 无法改变方法声明的返回类型，
 * 想输出统一体就只能每个控制器手写一遍；ResponseBodyAdvice 是 Spring MVC 官方的"响应体出站改写点"，
 * 一处装配全局生效，控制器保持干净。
 *
 * 不包装的情形（见 supports）：
 * - SseEmitter（SSE 流式）：包装会破坏 SSE 事件协议；
 * - String：会走 StringHttpMessageConverter，包成对象将导致转换器类型不匹配；
 * - 被 @RawResponse（方法级或类级）显式标记的接口，如 /health 探针。
 *
 * 幂等：异常处理器产出的 ApiResponse 原样返回，避免出现 code 套 code 的嵌套结构。
 */
@RestControllerAdvice
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (SseEmitter.class.isAssignableFrom(returnType.getParameterType())) {
            return false;
        }
        if (returnType.getParameterType() == String.class) {
            return false;
        }
        if (returnType.hasMethodAnnotation(RawResponse.class)) {
            return false;
        }
        Class<?> declaringClass = returnType.getContainingClass();
        return declaringClass == null || !declaringClass.isAnnotationPresent(RawResponse.class);
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
