package com.lender.assistant.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LlmCallLogger 单元测试：每次模型调用必须留痕（手册阶段4要求）。
 * 场景值 / 状态值 / 耗时落在 llm_call_log 表——这是简历上
 * "调用留痕统计单次接入推理成本" 的数据来源。
 */
class LlmCallLoggerTest {

    private JdbcTemplate jdbc;
    private LlmCallLogger logger;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        logger = new LlmCallLogger(jdbc);
    }

    @Test
    @DisplayName("调用成功：写一行 status=OK，耗时非负，返回值透传")
    void recordsSuccess() {
        String result = logger.record("extract", "Mock", () -> "抽取结果");

        assertEquals("抽取结果", result);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("llm_call_log"), args.capture());
        Object[] row = args.getValue();
        assertEquals("extract", row[0]);
        assertEquals("Mock", row[1]);
        assertEquals("OK", row[5]);
        assertInstanceOf(Integer.class, row[2]);
        assertTrue((Integer) row[2] >= 0, "耗时毫秒必须非负");
    }

    @Test
    @DisplayName("调用失败：写一行 status=FAIL 带错误信息，异常原样上抛")
    void recordsFailureAndRethrows() {
        RuntimeException boom = new RuntimeException("模拟模型 502");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> logger.record("qa", "DeepSeek", () -> { throw boom; }));

        assertSame(boom, thrown, "必须透传原始异常，不能吞掉换壳");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("llm_call_log"), args.capture());
        Object[] row = args.getValue();
        assertEquals("qa", row[0]);
        assertEquals("FAIL", row[5]);
        assertTrue(String.valueOf(row[6]).contains("502"));
    }

    @Test
    @DisplayName("留痕本身失败不反噬业务：日志写库异常不影响调用结果")
    void loggingFailureDoesNotBreakBusiness() {
        doThrow(new RuntimeException("log 表写不进去"))
                .when(jdbc).update(anyString(), any(Object[].class));

        String result = assertDoesNotThrow(() -> logger.record("qa", "Mock", () -> "业务结果"));
        assertEquals("业务结果", result, "留痕是旁路，不能挡住主链路");
    }

    @Test
    @DisplayName("手动打点：DeepSeek 版拿得到 token 数时走带 token 的重载")
    void manualRecordWithTokens() {
        logger.recordUsage("qa", "DeepSeek", 1200, 350, 156, "OK", null);

        verify(jdbc).update(contains("llm_call_log"), any(Object[].class));
    }
}
