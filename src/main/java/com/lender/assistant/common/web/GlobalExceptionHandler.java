package com.lender.assistant.common.web;

import com.lender.assistant.common.dto.ApiResponse;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把异常统一转成 {@link ApiResponse}(code/msg)，对外绝不暴露堆栈、内部类名与 SQL（§5.8）。
 *
 * 分支口径：
 * - BizException：HTTP 状态由 ErrorCode 反推（对齐 HTTP 语义的直接复用；1001 这类纯业务码无对应
 *   HTTP 状态，回落 500），body 保留原业务码——前端据 body.code 分支，不依赖 HTTP 状态。
 * - IllegalArgumentException：既有入参校验用裸 IllegalArgumentException（如各 controller 的 topK 校验），
 *   统一映射为 400 + 参数校验失败，避免落地成 500。
 * - 其它：一律 500 + 通用文案，完整堆栈只进日志。
 *
 * 为什么用 @ExceptionHandler 而不是 @Around 切面 catch：异常是 Spring MVC 的一等公民，
 * 官方异常解析链能正确处理内容协商与 HTTP 状态；切面里自行 catch 再拼响应体会与 MVC 的
 * 异常解析职责重叠，也容易漏掉未匹配到的异常类型。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        ErrorCode errorCode = e.errorCode();
        // WARN：业务异常是"预期内的失败"，但仍需留痕，便于回溯调用方传了什么
        log.warn("业务异常: code={}, msg={}", errorCode.code(), e.getMessage());
        return ResponseEntity.status(httpStatusOf(errorCode))
                .body(ApiResponse.fail(errorCode, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("入参校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        // ERROR + 堆栈：这是排查问题的唯一完整证据；对外只给通用文案，不回传 e.getMessage()
        log.error("未预期异常，已转为 500 通用响应", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    /** 错误码对齐 HTTP 语义时直用（400 / 404 / 500）；纯业务码（如 1001）无对应状态，回落 500 */
    private static HttpStatus httpStatusOf(ErrorCode errorCode) {
        HttpStatus status = HttpStatus.resolve(errorCode.code());
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }
}
