package com.lender.assistant.common.util;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 口令唯一口径：最少 8 个码点、最多 72 字节（UTF-8）。
 * 上限按字节而非字符：BCrypt 只处理前 72 字节，按字符校验会让中文口令被静默截断。
 */
class PasswordPolicyTest {

    @Test
    @DisplayName("8 个字符是下限，边界值放行")
    void eightCodePointsIsAccepted() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("12345678"));
    }

    @Test
    @DisplayName("7 个字符拒绝")
    void sevenCodePointsIsRejected() {
        BizException exception = assertThrows(BizException.class,
                () -> PasswordPolicy.validate("1234567"));
        assertEquals(ErrorCode.PARAM_INVALID, exception.errorCode());
    }

    @Test
    @DisplayName("72 字节边界放行，73 字节拒绝")
    void byteUpperBoundIsEnforced() {
        String exactly72Bytes = "密".repeat(24);
        String over72Bytes = "密".repeat(24) + "a";

        assertEquals(72, exactly72Bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertDoesNotThrow(() -> PasswordPolicy.validate(exactly72Bytes));
        assertThrows(BizException.class, () -> PasswordPolicy.validate(over72Bytes));
    }

    @Test
    @DisplayName("空口令拒绝")
    void blankIsRejected() {
        assertThrows(BizException.class, () -> PasswordPolicy.validate(null));
        assertThrows(BizException.class, () -> PasswordPolicy.validate("   "));
    }
}
