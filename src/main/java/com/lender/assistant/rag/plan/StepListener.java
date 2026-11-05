package com.lender.assistant.rag.plan;

import com.lender.assistant.rag.plan.model.StepEvent;

/** 步骤状态变化回调：编排层据此推送 SSE {@code step} 事件，执行器本身不依赖 SSE。 */
@FunctionalInterface
public interface StepListener {

    void onStep(StepEvent event);
}
