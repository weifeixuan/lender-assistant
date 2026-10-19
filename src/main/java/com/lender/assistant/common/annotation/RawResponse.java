package com.lender.assistant.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"原始响应"：跳过统一响应体包装（{@code ApiResponseBodyAdvice}）。
 *
 * 为什么需要它：统一包装只对"业务 JSON"成立；SSE 流、健康探针这类协议/探针响应必须保持原样——
 * 包一层 {code,msg,data} 会破坏 SSE 事件格式，也会让运维/验收脚本解析 /health 失败。
 * 可标在方法或类上：标在类上时对该类所有接口生效。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RawResponse {
}
