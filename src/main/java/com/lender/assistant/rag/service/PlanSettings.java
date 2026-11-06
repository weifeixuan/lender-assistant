package com.lender.assistant.rag.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 计划相关的稳定参数。
 *
 * 步数区间来自配置（可调业务口径）；工具单次超时是代码常量（票 10 口径：
 * 除整轮预算外不再开放分场景超时配置），这里保留构造入口只为单测能缩短等待。
 */
@Component
public class PlanSettings {

    /** 单步工具调用上限：10s（代码常量，不进配置表） */
    public static final long TOOL_TIMEOUT_MILLIS = 10_000L;
    /** 合成预留：剩余预算低于此值不再开新步（代码常量） */
    public static final long SYNTHESIS_RESERVE_MILLIS = 20_000L;

    private final int minSteps;
    private final int maxSteps;
    private final long toolTimeoutMillis;

    @Autowired
    public PlanSettings(@Value("${rag.plan.min-steps:2}") int minSteps,
                        @Value("${rag.plan.max-steps:5}") int maxSteps) {
        this(minSteps, maxSteps, TOOL_TIMEOUT_MILLIS);
    }

    public PlanSettings(int minSteps, int maxSteps, long toolTimeoutMillis) {
        this.minSteps = minSteps;
        this.maxSteps = maxSteps;
        this.toolTimeoutMillis = toolTimeoutMillis;
    }

    public int minSteps() {
        return minSteps;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public long toolTimeoutMillis() {
        return toolTimeoutMillis;
    }
}
