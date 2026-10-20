package com.lender.assistant.common.constant;

/**
 * 模型调用场景标识：{@code ResilientLlmExecutor.execute} 的 scene 参数，最终落库为 llm_call_log.scene。
 *
 * code 即落库/检索用的标签，desc 供日志与文档阅读。调用点直接传枚举，
 * 只在 LlmCallLogger 写库那一处取 {@code code()}——别处不再做枚举↔字符串转换。
 */
public enum LlmScene {

    /** 问答链路（SSE 流式，allowRetry=false） */
    QA("qa", "问答（SSE 流式）"),

    /** 结构化抽取链路（非流式，allowRetry=true） */
    EXTRACT("extract", "结构化抽取（非流式）"),

    /** 代码草稿生成链路（非流式，allowRetry=true） */
    DRAFT("draft", "代码草稿生成（非流式）");

    private final String code;
    private final String desc;

    LlmScene(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String code() {
        return code;
    }

    public String desc() {
        return desc;
    }
}
