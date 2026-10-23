package com.lender.assistant.common.dto;

import com.lender.assistant.common.exception.ErrorCode;

/**
 * 统一响应体：code / msg / data 三字段（AGENTS.md §5.8）。
 *
 * 为什么用 record 而不是 Map 手拼键名：契约必须固化在类型里——键名写错在编译期就暴露，
 * 前端也能拿到稳定的结构；Map 手拼的键名只有运行时才发现不一致（§0.3 禁止 Map 作对外契约）。
 *
 * 什么时候用：由 ResponseWrapAspect 自动包裹普通 controller 的返回值，
 * 业务代码一般不需要手工构造；手工构造的场景是全局异常处理器拼失败响应。
 * SSE 等原始响应由 @RawResponse / 返回类型排除，不走这里。
 *
 * @param code 与 {@link ErrorCode#code()} 对齐；成功恒为 200
 * @param msg  可读文案：成功固定 "ok"，失败给具体原因（不泄露堆栈/内部细节）
 * @param data 业务载荷；失败时为 null
 */
public record ApiResponse<T>(int code, String msg, T data) {

    /** 成功响应：code=200、msg="ok" */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.defaultMessage(), data);
    }

    /** 失败响应：code 取错误码，msg 取具体文案，data 恒为 null */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }
}
