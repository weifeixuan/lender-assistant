package com.lender.assistant.common.exception;

/**
 * 业务错误码：把"失败语义"固化成可枚举的契约，供 {@link BizException} 携带、
 * 由全局异常处理器转成对外响应。
 *
 * 取值口径：与 HTTP 语义对齐的（400 / 404 / 500）直接复用 HTTP 状态码，
 * 便于全局处理器用 {@code HttpStatus.resolve} 反推 HTTP 状态；纯业务语义的
 * （如模型调用失败）另起 1001 段，不挤占 HTTP 状态码空间。前端据 code 分支判断，
 * 不靠文案匹配——文案可改，code 不可改。
 */
public enum ErrorCode {

    /** 成功：统一响应体 code 恒为 200 */
    SUCCESS(200, "ok"),
    /** 入参校验失败：对应 HTTP 400 */
    PARAM_INVALID(400, "参数校验失败"),
    /** 资源不存在：对应 HTTP 404 */
    NOT_FOUND(404, "资源不存在"),
    /** 文档不存在：docId 未入库时查询切片等操作的可区分业务码（HTTP 侧同样落 404） */
    DOC_NOT_FOUND(404, "文档不存在"),
    /** 模型调用失败：业务码 1001，HTTP 侧统一落 500 */
    LLM_FAILED(1001, "模型调用失败"),
    /** 未预期的系统错误：对应 HTTP 500，对外只给通用文案（细节仅入日志） */
    INTERNAL_ERROR(500, "系统错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
