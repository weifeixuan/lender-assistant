package com.lender.assistant.rag.plan;

/**
 * 计划生成的模型调用入口：与路由侧同构，只负责一次非流式补全。
 * 抽成函数式接口是为了单测能直接传 lambda，不必起 Spring 上下文。
 */
@FunctionalInterface
public interface PlannerModelClient {

    String complete(String prompt);
}
