package com.lender.assistant.rag.routing;

/**
 * 路由模型调用的唯一缝：把「模型输出」与「分类规则」解耦。
 * 真实实现走 OpenAI 兼容非流式接口；单测直接替换为固定输出。
 */
@FunctionalInterface
public interface RoutingModelClient {

    /** 传入完整 prompt，返回模型原始文本（期望 JSON） */
    String complete(String prompt);
}
