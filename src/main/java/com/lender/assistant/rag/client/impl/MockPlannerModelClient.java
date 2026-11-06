package com.lender.assistant.rag.client.impl;

import com.lender.assistant.rag.client.PlannerModelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * mock provider 下的占位计划模型。
 *
 * mock 模式在编排层直接跳过计划（见票 03/07），本类只为保证 Bean 图完整，
 * 不会被问答链路调用；返回空计划而不是伪造业务步骤。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "mock", matchIfMissing = true)
public class MockPlannerModelClient implements PlannerModelClient {

    @Override
    public String complete(String prompt) {
        return "{\"goal\":\"\",\"steps\":[]}";
    }
}
