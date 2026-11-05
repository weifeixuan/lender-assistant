package com.lender.assistant.rag.plan;

/** 预算计时用的时钟；单测注入可控实现，生产用系统时间。 */
@FunctionalInterface
public interface PlanClock {

    long nowMillis();
}
