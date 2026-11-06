package com.lender.assistant.rag.service;

/**
 * 整轮预算：从路由开始时构建，覆盖路由、计划、步骤与合成。
 * 时钟可注入，单测可精确模拟「顺利 / 最坏 / 剩余不足」而不必真等。
 */
public final class PlanBudget {

    private final long totalBudgetMillis;
    private final long startedAt;
    private final PlanClock clock;

    public PlanBudget(long totalBudgetMillis, PlanClock clock) {
        this.totalBudgetMillis = totalBudgetMillis;
        this.clock = clock;
        this.startedAt = clock.nowMillis();
    }

    public long remainingMillis() {
        return totalBudgetMillis - (clock.nowMillis() - startedAt);
    }

    /** 剩余预算是否仍够「跳过剩余步骤 + 保留合成时间」的门槛 */
    public boolean canStartStep(long synthesisReserveMillis) {
        return remainingMillis() >= synthesisReserveMillis;
    }
}
