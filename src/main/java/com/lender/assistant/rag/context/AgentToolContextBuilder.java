package com.lender.assistant.rag.context;

import java.util.Map;

/**
 * 计划步骤可调用的最小工具抽象：一个名字 + 一次确定性调用。
 * 工具不允许回调模型，也不产生流式输出（ADR-0014）。
 */
public interface AgentToolContextBuilder {

    /** 计划里的 tool 标识，如 kb.search */
    String name();

    /** @param args 计划期字面量参数；非法时返回 ARGS_INVALID 而不是抛异常 */
    ToolResultContextBuilder call(Map<String, Object> args, ToolContextContextBuilder context);
}
