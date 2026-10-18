package com.lender.assistant.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.lender.assistant.resilience.mapper.LlmCallLogMapper;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * ResilientLlmExecutor 测试：熔断状态机 + 重试 + chaos 故障注入的完整红绿循环。
 *
 * 配置：failureThreshold=3（连续 3 次执行失败→熔断）、maxAttempts=2（每次执行内重试 2 次）。
 * 装配顺序 CB 在外 Retry 在内：重试消化单次执行内的瞬时抖动，
 * 熔断器只统计"整次执行"的成败——这个顺序是韧性设计的标准答案。
 */
class ResilientLlmExecutorTest {

    private LlmCallLogger logger;

    @BeforeEach
    void setUp() {
        logger = new LlmCallLogger(mock(LlmCallLogMapper.class));
    }

    private ResilientLlmExecutor newExecutor(boolean chaos) {
        return new ResilientLlmExecutor(logger, 3, 10, 2, chaos);
    }

    @Test
    @DisplayName("正常路径：透传返回值，无故障注入")
    void successPassthrough() {
        assertEquals("ok", newExecutor(false).execute("qa", "Mock", () -> "ok", false));
    }

    /** chaos 包在 call 内层（模拟"调用已发出但失败"），所以被测 supplier 正常返回+计数 */
    private final AtomicInteger calls = new AtomicInteger();
    private Supplier<String> countedCall() {
        return () -> {
            calls.incrementAndGet();
            return "model-ok";
        };
    }

    @Test
    @DisplayName("chaos=true 且允许重试：异常重试后仍上抛，supplier 被调用 maxAttempts 次")
    void chaosRetriesThenThrows() {
        assertThrows(IllegalStateException.class,
                () -> newExecutor(true).execute("extract", "Mock", countedCall(), true));
        assertEquals(2, calls.get(), "重试语义：一次执行最多 maxAttempts 次尝试");
    }

    @Test
    @DisplayName("chaos=true 且禁用重试（SSE 场景）：只调用一次，不重放已推给用户的 token")
    void noRetryModeInvokesOnce() {
        assertThrows(IllegalStateException.class,
                () -> newExecutor(true).execute("qa", "Mock", countedCall(), false));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("连续失败达到阈值 → 熔断 OPEN，后续调用快速失败且不再触达 supplier")
    void circuitOpensAfterThreshold() {
        ResilientLlmExecutor executor = newExecutor(true);

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class,
                    () -> executor.execute("qa", "Mock", countedCall(), false));
        }
        assertEquals(3, calls.get());

        // 第 4 次：熔断器已 OPEN，快速失败，supplier 完全不被触达
        assertThrows(RuntimeException.class,
                () -> executor.execute("qa", "Mock", countedCall(), false));
        assertEquals(3, calls.get(), "熔断打开后业务代码必须零调用（快速失败）");
    }

    @Test
    @DisplayName("chaos=false 时熔断器不受影响：正常调用持续通过")
    void noChaosNeverOpens() {
        ResilientLlmExecutor executor = newExecutor(false);
        for (int i = 0; i < 10; i++) {
            assertEquals("ok", executor.execute("qa", "Mock", () -> "ok", false));
        }
    }
}
