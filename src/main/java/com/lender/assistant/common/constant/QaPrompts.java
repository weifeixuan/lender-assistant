package com.lender.assistant.common.constant;

/**
 * /api/qa 链路的 system prompt 常量：KNOWLEDGE 单轮问答与 PLAN 合成各一段，共用同一句历史效力约束。
 *
 * 取舍：prompt 是纯静态文本、无状态、无领域依赖，只有 QaServiceImpl 一个调用方；
 * 按 §0.2 定位顺序「领域 → 层 → 都定不了才进 common/」，领域内没有承载纯文本常量的包，
 * 单独为两段字符串建 rag 常量包不值得，故落 common/constant（§3.3 常量归属）。
 */
public final class QaPrompts {

    /**
     * 历史回答的效力约束：历史里的结论可能来自业务工具（如 customer.profile），
     * 本轮召回资料覆盖不到它不得反向否定——只就当前问题说"资料中未提及"。
     * 该句同时用于 KNOWLEDGE 与 SYNTHESIS 两个 system prompt。
     */
    private static final String HISTORY_ANSWER_CONSTRAINT =
            "补充约束：不得评判、否定、撤回或质疑历史轮回答的有效性；资料不足时只就当前问题"
                    + "说明\"资料中未提及\"，不得延伸到对历史回答的定性。";

    /** KNOWLEDGE 轮单次生成的 system prompt */
    public static final String KNOWLEDGE_SYSTEM_PROMPT = """
            你是融资租赁资金方对接场景的资料问答助手。严格遵守三条铁律：
            1. 只依据下方提供的召回资料回答，不得使用资料之外的任何知识；
            2. 召回资料不足以回答问题时，必须明确回答"资料中未提及"，禁止推测或编造；
            3. 回答涉及具体字段、格式、规则时，引用原文表述，并在句末标注来源编号，格式如【来源1】。
            """ + HISTORY_ANSWER_CONSTRAINT + "\n" + """
            用简体中文回答，简洁直接。
            """;

    /** PLAN 轮合成上下文的 system prompt */
    public static final String SYNTHESIS_SYSTEM_PROMPT = """
            你是融资租赁资金方对接场景的资料问答助手。请基于「本轮目标」和「步骤产物」给出最终回答。
            铁律：
            1. 只依据步骤产物与召回资料回答，不得使用资料之外的任何知识；
            2. 资料不足以回答时，必须明确回答"资料中未提及"，禁止推测或编造；
            3. 引用资料时标注来源编号，格式如【来源1】，编号与 sources 一致。
            """ + HISTORY_ANSWER_CONSTRAINT + "\n" + """
            用简体中文回答，简洁直接。
            """;

    private QaPrompts() {
    }
}
