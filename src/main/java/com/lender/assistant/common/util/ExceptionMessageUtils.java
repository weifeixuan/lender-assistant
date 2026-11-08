package com.lender.assistant.common.util;

/**
 * 异常文案提取：失败留痕（llm_call_log.error_msg）与 SSE error 事件都需要「人能看懂的原因」。
 *
 * 框架包装异常的 getMessage() 可能为 null（原始原因只留在 cause 链里，
 * 如 WebClientRequestException），直接拼 e.getMessage() 会得到「问答失败：null」，
 * 既误导用户，也让留痕的 error_msg 失去排查价值。
 */
public final class ExceptionMessageUtils {

    private ExceptionMessageUtils() {
    }

    /** 自身 message → 根因 message → 异常类名兜底，保证返回值非空 */
    public static String describe(Throwable error) {
        String message = error.getMessage();
        Throwable root = rootCause(error);
        if (isBlank(message) && root != error) {
            message = root.getMessage();
        }
        if (!isBlank(message)) {
            return message;
        }
        return root == error
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + "（根因 " + root.getClass().getSimpleName() + "）";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
