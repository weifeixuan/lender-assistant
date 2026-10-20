package com.lender.assistant.resilience;

import com.lender.assistant.common.constant.LlmScene;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 模型调用韧性执行器（阶段4）：熔断 + 重试 + chaos 故障注入 + 留痕，四合一入口。
 *
 * 手册要求"超时/重试/熔断 + 留痕；熔断时走人工流程"，各机制归属：
 * - 熔断（本类）：连续 failureThreshold 次执行失败 → OPEN，快速失败不再触达模型；
 * - 重试（本类，仅非流式）：SSE 场景 token 已推给用户、不可重放，qa 传 allowRetry=false；
 * - 超时（不在此类）：DeepSeek 流自身 .timeout(60s)、SseEmitter 120s 兜底——
 *   TimeLimiter 需要额外线程池，对当前规模是过度设计，等真有多模型并发再说；
 * - 留痕（本类委托 LlmCallLogger）：整次执行（含重试）记一行，失败带原因；
 * - chaos（本类）：rag.resilience.chaos=true 时调用前直接抛故障，
 *   供自动化测试与降级演练确定性触发，不碰真实容器。
 *
 * 装配顺序 CB 在外 Retry 在内（韧性设计标准答案）：
 * 重试消化单次执行内的瞬时抖动，熔断器只统计"整次执行"的成败——
 * 否则一次执行的重试会被熔断器当成多次失败，提前误开熔断。
 *
 * 简化声明：当前只有一个模型后端，qa/extract 共用一个熔断器实例是可接受的；
 * 将来多供应商时按 provider 拆分实例即可（Map<String, CircuitBreaker>）。
 */
@Component
public class ResilientLlmExecutor {

    /** 重试间隔：瞬时抖动（网络抖动/限流）给一秒内的短退避即可，过长会拖垮用户等待 */
    private static final long RETRY_WAIT_MILLIS = 50;
    /** 失败率阈值（%）：窗口内失败占比超过它就熔断 */
    private static final int FAILURE_RATE_THRESHOLD_PERCENT = 50;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final LlmCallLogger logger;
    private final boolean chaos;

    public ResilientLlmExecutor(LlmCallLogger logger,
                                @Value("${rag.resilience.failure-threshold:3}") int failureThreshold,
                                @Value("${rag.resilience.wait-duration-sec:30}") int waitDurationSec,
                                @Value("${rag.resilience.max-attempts:2}") int maxAttempts,
                                @Value("${rag.resilience.chaos:false}") boolean chaos) {
        this.logger = logger;
        this.chaos = chaos;
        // 熔断参数：滑动窗口=阈值本身，即"连续 N 次失败即熔断"，语义对演示与测试最直观
        this.circuitBreaker = CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .slidingWindowSize(failureThreshold)
                .minimumNumberOfCalls(failureThreshold)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD_PERCENT)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationSec))
                .build());
        this.retry = Retry.of("llm", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(RETRY_WAIT_MILLIS))
                .build());
    }

    /**
     * 包住一次模型调用。
     * @param allowRetry 非流式（如抽取）true；SSE 流式（问答）必须 false
     */
    public <T> T execute(LlmScene scene, String provider, Supplier<T> call, boolean allowRetry) {
        // chaos 包在 call 内层：语义是"调用已发出但失败"（重试会再次发出调用），
        // 而不是"调用根本没发出去"——后者连重试/熔断的观察面都没有
        Supplier<T> chain = () -> {
            T result = call.get();
            if (chaos) {
                throw new IllegalStateException("chaos: 模拟模型故障（rag.resilience.chaos=true）");
            }
            return result;
        };
        Supplier<T> withRetry = allowRetry ? Retry.decorateSupplier(retry, chain) : chain;
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, withRetry);
        return logger.record(scene, provider, guarded::get);
    }

    /** 熔断器当前状态（前端降级横幅 / 验收演练观察用） */
    public String circuitState() {
        return circuitBreaker.getState().name();
    }
}
