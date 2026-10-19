package com.lender.assistant.common.exception;

/**
 * 业务异常：携带 {@link ErrorCode} 与可读文案，由全局异常处理器统一转成 code/msg 错误响应
 * （AGENTS.md §5.3）。
 *
 * 为什么继承 RuntimeException：业务失败是运行期场景，不强制调用方逐层声明；收敛到本类后，
 * 业务代码禁止再用裸 RuntimeException 做流程控制——裸异常无法携带 code，前端拿不到可分支的语义。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 只给错误码：文案取 {@link ErrorCode#defaultMessage()} */
    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /** 给错误码 + 自定义可读文案（保留上下文，如 "docId 必填"） */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
