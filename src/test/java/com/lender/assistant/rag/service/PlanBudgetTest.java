package com.lender.assistant.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanBudgetTest {

    private static final long TOTAL = 90_000L;

    @Test
    @DisplayName("顺利路径：耗时 40s，仍有 50s 且可开新步")
    void fortySecondsStillHasBudget() {
        AtomicLong now = new AtomicLong(0);
        PlanBudget budget = new PlanBudget(TOTAL, now::get);
        now.set(40_000);

        assertTrue(budget.remainingMillis() > 0);
        assertTrue(budget.canStartStep(PlanSettings.SYNTHESIS_RESERVE_MILLIS));
    }

    @Test
    @DisplayName("最坏路径：耗时 60s，剩余 30s 仍可开新步")
    void sixtySecondsStillCanStartStep() {
        AtomicLong now = new AtomicLong(0);
        PlanBudget budget = new PlanBudget(TOTAL, now::get);
        now.set(60_000);

        assertTrue(budget.canStartStep(PlanSettings.SYNTHESIS_RESERVE_MILLIS));
    }

    @Test
    @DisplayName("剩余不足合成预留（75s）：不再开新步")
    void seventyFiveSecondsCannotStartStep() {
        AtomicLong now = new AtomicLong(0);
        PlanBudget budget = new PlanBudget(TOTAL, now::get);
        now.set(75_000);

        assertFalse(budget.canStartStep(PlanSettings.SYNTHESIS_RESERVE_MILLIS));
    }

    @Test
    @DisplayName("预算耗尽（90s）：剩余为 0，不再合成")
    void exhaustedBudgetHasNoRemaining() {
        AtomicLong now = new AtomicLong(0);
        PlanBudget budget = new PlanBudget(TOTAL, now::get);
        now.set(90_000);

        assertFalse(budget.remainingMillis() > 0);
    }
}
