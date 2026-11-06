package com.lender.assistant.rag.client.impl;

import com.lender.assistant.rag.client.RoutingModelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * mock provider 下的占位路由模型。
 *
 * mock 模式在编排层直接跳过路由与计划（见票 03），本类只为保证 Bean 图完整，
 * 不会被问答链路调用；不做关键词规则、不伪造分类结果。
 */
@Component
@ConditionalOnProperty(name = "rag.chat.provider", havingValue = "mock", matchIfMissing = true)
public class MockRoutingModelClient implements RoutingModelClient {

    @Override
    public String complete(String prompt) {
        return "{\"route\":\"KNOWLEDGE\",\"confidence\":1.0,\"reason\":\"mock\","
                + "\"irrelevantCategory\":\"NONE\"}";
    }
}
