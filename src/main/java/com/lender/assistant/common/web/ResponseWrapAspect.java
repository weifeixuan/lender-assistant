package com.lender.assistant.common.web;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.dto.ApiResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一响应包装切面：把 @RestController 的普通返回值包成 {@link ApiResponse}(code/msg/data)。
 *
 * 为什么用切面包装而不是 ResponseBodyAdvice（修订 ADR-0001）：MVC 选消息转换器看的是返回值的
 * 运行时类型而非声明类型，@Around 把 DraftResponse 换成 ApiResponse 返回后序列化完全正常——
 * 切面包装让"写出的响应体"与"日志切面记录的出参"是同一个对象，日志即真实响应，无口径漂移。
 * 原注释中"@Around 无法改变声明返回类型"的理由不成立（受限的只是需要逐类排除特殊返回类型）。
 *
 * 与日志切面的顺序契约（关键）：本切面 @Order(2) 在内层，ControllerLogAspect @Order(1) 在外层。
 * 外层 proceed() 返回的已是包装后的 ApiResponse，出参日志天然含 code/msg/data。
 * 两个切面的 @Order 必须显式声明——Spring 对无序切面的相对顺序不做保证；顺序错误不会报错，
 * 只会让出参日志悄悄变回原始值，由 ResponseWrapAspectTest 的顺序锁定用例兜底。
 *
 * 不包装的情形（与原 ApiResponseBodyAdvice.supports() 口径一致，规则随本类迁移）：
 * - 已是 ApiResponse：异常处理器等场景手工产出，二次包装会变成 code 套 code；
 * - SseEmitter（SSE 流式）：包装会破坏 SSE 事件协议；
 * - ResponseEntity：响应状态/头自带语义，整体包装会把它塞进 data；
 * - 被 @RawResponse（方法级或类级）显式标记的接口，如 /health 探针。
 *
 * 与原 Advice 行为的差异：原实现对 String 返回类型不包装（StringHttpMessageConverter 限制）；
 * 切面包装下转换器按运行时类型选择，String 也能正确包上。当前代码库无 String 返回的接口，无实际影响。
 *
 * 长期约束（Spring AOP 机制边界，写代码前须知）：
 * - controller 方法不能是 final / static，不能同类内部自调用，否则代理失效、包装缺失；
 * - 非 @RestController 的 handler（如普通 @Controller）不在切点内，不会被包装。
 */
@Aspect
@Component
@Order(2)
public class ResponseWrapAspect {

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void restControllerMethods() {
    }

    @Around("restControllerMethods()")
    public Object wrap(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();
        if (result instanceof ApiResponse || result instanceof SseEmitter
                || result instanceof ResponseEntity || isRawResponse(joinPoint)) {
            return result;
        }
        return ApiResponse.ok(result);
    }

    /** @RawResponse 可标在方法或类上，标在类上时对该类所有接口生效 */
    private static boolean isRawResponse(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getMethod().isAnnotationPresent(RawResponse.class)
                || signature.getDeclaringType().isAnnotationPresent(RawResponse.class);
    }
}
