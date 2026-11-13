package com.lender.assistant.common.util;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;

/**
 * 口令唯一口径：最少 8 个码点、最多 72 个字节（UTF-8）。
 *
 * 上限按字节而非字符：BCrypt 只处理前 72 字节，按字符校验会让中文口令被静默截断，
 * 因此超限一律 400，不做截断后放行。
 */
public final class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "口令不能为空");
        }
        if (password.codePointCount(0, password.length()) < MIN_CODE_POINTS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "口令至少 " + MIN_CODE_POINTS + " 个字符");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "口令不能超过 " + MAX_BYTES + " 字节");
        }
    }
}
