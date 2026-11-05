package com.lender.assistant.rag.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.plan.model.ExecutionPlan;
import com.lender.assistant.rag.plan.model.PlanStep;
import com.lender.assistant.rag.tool.PlanToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划生成与整单校验。
 *
 * 计划是「模型提案、代码裁决」：模型只输出 JSON 提案，是否可采用由本类的确定性规则决定。
 * 任何一项不合法（JSON 非法、步数越界、工具不在白名单、args 非字面量、缺 kb.search、
 * stepId/order 重复或乱序）都整单弃用，调用方退回 KNOWLEDGE 单次检索链路——
 * 「部分执行一个坏计划」比「不执行」更危险。
 */
@Service
public class PlanAndExecuteService {

    private static final String KB_SEARCH = "kb.search";
    private static final String MARKDOWN_FENCE = "```";
    private static final String STEP_ID_PREFIX = "s";
    private static final int MAX_ATTEMPTS = 2;

    private final PlannerModelClient client;
    private final ObjectMapper objectMapper;
    private final PlanToolRegistry registry;
    private final int minSteps;
    private final int maxSteps;

    public PlanAndExecuteService(PlannerModelClient client, ObjectMapper objectMapper,
                                 PlanToolRegistry registry,
                                 @Value("${rag.plan.min-steps:2}") int minSteps,
                                 @Value("${rag.plan.max-steps:5}") int maxSteps) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.minSteps = minSteps;
        this.maxSteps = maxSteps;
    }

    /** 无知识库目录时的入口（目录只影响提案质量，不参与校验） */
    public ExecutionPlan plan(String question, String previousQuestion) {
        return plan(question, previousQuestion, "");
    }

    public ExecutionPlan plan(String question, String previousQuestion, String knowledgeCatalog) {
        try {
            ModelPayload payload = objectMapper.readValue(
                    cleanModelText(complete(buildPrompt(question, previousQuestion, knowledgeCatalog))),
                    ModelPayload.class);
            return validate(payload);
        } catch (Exception ex) {
            return ExecutionPlan.invalid();
        }
    }

    /** 非流式调用，最多尝试 2 次；两次都失败由调用方整单降级 */
    private String complete(String prompt) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return client.complete(prompt);
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
        }
        throw lastFailure == null ? new IllegalStateException("计划模型未返回内容") : lastFailure;
    }

    private ExecutionPlan validate(ModelPayload payload) {
        if (payload == null || payload.steps() == null) {
            return ExecutionPlan.invalid();
        }
        int count = payload.steps().size();
        if (count < minSteps || count > maxSteps) {
            return ExecutionPlan.invalid();
        }
        List<PlanStep> steps = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            PlanStep step = toStep(payload.steps().get(index), index + 1);
            if (step == null) {
                return ExecutionPlan.invalid();
            }
            steps.add(step);
        }
        return containsKbSearch(steps) ? ExecutionPlan.valid(Objects.toString(payload.goal(), ""), steps)
                : ExecutionPlan.invalid();
    }

    /**
     * 单步校验：order 必须等于位置序号（同时挡住重复 order），
     * stepId 必须等于 s{order}（同时挡住重复 stepId），工具必须命中白名单。
     *
     * @return null 表示该步不合法
     */
    private PlanStep toStep(RawStep raw, int expectedOrder) {
        if (raw == null || raw.order() == null || raw.order() != expectedOrder) {
            return null;
        }
        if (!(STEP_ID_PREFIX + expectedOrder).equals(raw.stepId())) {
            return null;
        }
        if (raw.tool() == null || !registry.contains(raw.tool())) {
            return null;
        }
        if (!argsAreLiterals(raw.args())) {
            return null;
        }
        return new PlanStep(raw.stepId(), raw.order(), Objects.toString(raw.title(), ""),
                raw.tool(), raw.args());
    }

    /** args 只允许扁平字面量：null / 嵌套对象 / 数组一律视为非法 */
    private static boolean argsAreLiterals(Map<String, Object> args) {
        if (args == null) {
            return false;
        }
        for (Object value : args.values()) {
            if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsKbSearch(List<PlanStep> steps) {
        for (PlanStep step : steps) {
            if (KB_SEARCH.equals(step.tool())) {
                return true;
            }
        }
        return false;
    }

    private String buildPrompt(String question, String previousQuestion, String knowledgeCatalog) {
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
                nullToEmpty(knowledgeCatalog), String.join(", ", registry.names().stream().sorted().toList()),
                minSteps, maxSteps);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 去掉模型偶发的 ```json 包裹，失败交给 Jackson 抛异常后走降级 */
    private static String cleanModelText(String text) {
        String trimmed = Objects.toString(text, "").trim();
        if (!trimmed.startsWith(MARKDOWN_FENCE)) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf(MARKDOWN_FENCE);
        return firstLineEnd > 0 && lastFence > firstLineEnd
                ? trimmed.substring(firstLineEnd + 1, lastFence).trim() : trimmed;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelPayload(String goal, List<RawStep> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawStep(String stepId, Integer order, String title, String tool,
                           Map<String, Object> args) {
    }
}
