package com.lender.assistant.rag.context;

/** 工具执行失败的固定错误码；落库与可观测统一用它，禁止散落字符串。 */
public enum ToolErrorCodeContextBuilder {
    ARGS_INVALID,
    TOOL_NOT_FOUND,
    TOOL_TIMEOUT,
    TOOL_FAILED,
    TOOL_DEGRADED
}
