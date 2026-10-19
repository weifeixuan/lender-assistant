package com.lender.assistant.common.web;

import com.lender.assistant.common.dto.ApiResponse;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把异常统一转成 {@link ApiResponse}(code/msg)，对外绝不暴露堆栈、内部类名与 SQL（§5.8）。
 *
 * 分支口径：
 * - BizException：HTTP 状态由 ErrorCode 反推（对齐 HTTP 语义的直接复用；1001 这类纯业务码无对应
 *   HTTP 状态，回落 500），body 保留原业务码——前端据 body.code 分支，不依赖 HTTP 状态。
 * - IllegalArgumentException：既有入参校验用裸 IllegalArgumentException（如各 controller 的 topK 校验），
 *   统一映射为 400 + 参数校验失败，避免落地成 500。
 * - Spring MVC 客户端错误：路由不存在 → 404；报文不可解析 / 参数类型不匹配 / 必填参数缺失 → 400。
 *   这些是"请求方用法错误"而非服务端故障，不单独处理会被兜底成 500，误导调用方。
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

    /**
     * 路由不存在（NoResourceFoundException 由静态资源处理器抛出；NoHandlerFoundException 需
     * 开启 throw-exception-if-no-handler-found）——与业务无关，是调用方把路径写错了，归 404。
     *
     * 单测覆盖不到 MVC 异常解析链，本分支是真实启动冒烟才发现"错误路径返回 500"后补的。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        log.warn("请求路径不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage()));
    }

    /**
     * 报文不可解析 / 参数类型不匹配 / 必填参数缺失 —— 均为调用方构造请求有误，归 400。
     * 文案用 ErrorCode 默认值而非 e.getMessage()：框架异常信息是技术细节（如 Jackson 的解析位置），
     * 对调用方无意义，且触碰"不暴露内部细节"红线（§5.8）。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleClientRequestError(Exception e) {
        log.warn("请求报文/参数不合法: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.defaultMessage()));
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
