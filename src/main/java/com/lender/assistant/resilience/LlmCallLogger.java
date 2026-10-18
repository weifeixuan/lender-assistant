package com.lender.assistant.resilience;

import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
import com.lender.assistant.resilience.model.LlmCallLogRecord;
import org.springframework.stereotype.Repository;

import java.util.function.Supplier;

/**
 * 模型调用留痕器（每次模型调用留痕——耗时/token/成本）。
 *
 * 设计：装饰器/旁路模式——业务代码只写 logger.record(scene, provider, () -> ...)，
 * 留痕对主链路零侵入；且留痕写库失败绝不反噬业务（旁路降级）。
 *
 * 这是简历数字"单次接入推理成本 XX 元"的数据来源：
 * llm_call_log 按行累计 prompt/completion token，成本 = token × 单价（配置价目）。
 * Mock 调用 token 记 NULL——mock 没有真实计量，诚实留空比编个数字好。
 *
 * 数据访问已迁到 LlmCallLogMapper（§11）：本类只保留"包住一次调用 + 旁路降级"的编排职责，
 * 不再持有 JdbcTemplate。旁路降级（catch 后不上抛）是刻意的——见 write()。
 */
@Repository
public class LlmCallLogger {

    private final LlmCallLogMapper mapper;

    public LlmCallLogger(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 包住一次模型调用：成功记 OK，失败记 FAIL 并原样上抛异常（不吞不换壳） */
    public <T> T record(String scene, String provider, Supplier<T> call) {
        long t0 = System.currentTimeMillis();
        try {
            T result = call.get();
            write(scene, provider, (int) (System.currentTimeMillis() - t0), null, null, "OK", null);
            return result;
        } catch (RuntimeException e) {
            write(scene, provider, (int) (System.currentTimeMillis() - t0), null, null, "FAIL", e.getMessage());
            throw e;
        }
    }

    /**
     * 手动打点：真实模型实现自己拿到 token 计量时调用
     * （Spring AI 的 Usage 在响应里，流式场景由实现方汇总后打点）。
     */
    public void recordUsage(String scene, String provider, Integer promptTokens,
                            Integer completionTokens, int durationMs, String status, String errorMsg) {
        write(scene, provider, durationMs, promptTokens, completionTokens, status, errorMsg);
    }

    private void write(String scene, String provider, int durationMs, Integer promptTokens,
                       Integer completionTokens, String status, String errorMsg) {
        try {
            mapper.insert(new LlmCallLogRecord(scene, provider, durationMs,
                    promptTokens, completionTokens, status, errorMsg));
        } catch (RuntimeException ignored) {
            // 留痕是旁路：log 表写不进去（没跑 docs/schema.sql 等）不允许拖垮问答/抽取主链路
        }
    }
}
