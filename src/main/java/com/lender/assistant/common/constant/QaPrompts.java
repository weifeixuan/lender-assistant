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

    /**
     * 路由 prompt：让模型在 IRRELEVANT / KNOWLEDGE / PLAN 三分类中做判断。
     * 迁到常量类的目的：prompt 与解析契约一起评审，避免散落在服务实现里被静默改坏。
     */
    public static String routePrompt(String previousQuestion, String question, String previousRoute,
                                     String knowledgeCatalog) {
        return """
               你是问题路由器。判断「当前问题」属于哪一类。

               【输入】
               上一轮问题（仅用于解析指代，不要并入本轮目标）：%s
               当前问题（本轮目标）：%s
               上一轮路由：%s
               知识库目录：%s

               【分类】
               IRRELEVANT：问候、闲聊、越权，或与资方接入完全无关
               KNOWLEDGE：依据知识库回答，单次检索即可覆盖
               PLAN：需要多个信息点，或跨文档比较/汇总

               【规则】
               1. 当前问题能独立理解时，忽略上一轮问题。
               2. 上一轮路由为 KNOWLEDGE/PLAN 时，指代不明的追问不要判 IRRELEVANT。
               3. 项目相关但资料里没有答案的问题，判 KNOWLEDGE，不要判 IRRELEVANT。

               只输出 JSON：{"route":"...","confidence":0.0,"reason":"...","irrelevantCategory":"NONE"}
               """.formatted(nullToEmpty(previousQuestion), question,
                previousRoute == null ? "无" : previousRoute,
                nullToEmpty(knowledgeCatalog));
    }

    /**
     * 计划 prompt：把当前问题拆成若干线性 kb.search / 业务工具步骤。
     * 规则文本与 PlanAndExecuteService 的校验逻辑是同一条契约，改一处必须同步。
     */
    public static String planPrompt(int minSteps, int maxSteps, String previousQuestion, String question,
                                    String knowledgeCatalog, String toolNames) {
        return """
               你是任务计划器。把「当前问题」拆成 %d–%d 个确定性工具步骤。

               【输入】
               上一轮问题（仅用于解析指代，不要并入本轮目标）：%s
               当前问题（本轮目标）：%s
               知识库目录（仅 docId + docTitle）：%s
               可用工具：%s

               【规则】
               1. 当前问题能独立理解时，忽略上一轮问题。
               2. 步骤数 %d–%d，order 从 1 连续递增，stepId 依次为 s1–sN。
               3. 至少包含一个 kb.search。
               4. args 只能写字面量：抽不出 customerId / productCode 就不要建该步。
               5. 不做 DAG，不设 dependsOn，步骤按顺序线性执行。

               只输出 JSON：{"goal":"...","steps":[{"stepId":"s1","order":1,"title":"...","tool":"...","args":{}}]}
               """.formatted(minSteps, maxSteps, nullToEmpty(previousQuestion), question,
                nullToEmpty(knowledgeCatalog), toolNames, minSteps, maxSteps);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
