package com.lender.assistant.common.constant;

import java.time.Duration;

/**
 * 模型调用共享数值常量（§3.3：纯数值常量归 *Constants，禁实例化）。
 */
public final class LlmCallConstants {

    /**
     * 非流式模型单次调用超时（10s）：路由 / 计划 / 摘要三个非流式客户端共用。
     * Rule of Three 抽取自 OpenAiRoutingModelClient 与 OpenAiPlannerModelClient 的类内重复常量；
     * 超时用 Reactor timeout 而非 Resilience4j TimeLimiter——后者需要额外线程池，
     * 对单次非流式调用是过度设计（详见 OpenAiRoutingModelClient Javadoc）。
     */
    public static final Duration NON_STREAM_TIMEOUT = Duration.ofSeconds(10);

    private LlmCallConstants() {
    }
}
