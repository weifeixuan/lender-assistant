package com.lender.assistant.rag.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.common.constant.QaPrompts;
import com.lender.assistant.common.util.ThreadPoolFactory;
import com.lender.assistant.rag.client.PlannerModelClient;
import com.lender.assistant.rag.model.dto.ExecutionPlanDTO;
import com.lender.assistant.rag.model.dto.PlanExecutionResultDTO;
import com.lender.assistant.rag.model.dto.PlanStepDTO;
import com.lender.assistant.rag.model.dto.StepEventDTO;
import com.lender.assistant.rag.model.dto.StepOutcomeDTO;
import com.lender.assistant.rag.model.dto.StepStatus;
import com.lender.assistant.rag.tool.AgentTool;
import com.lender.assistant.rag.tool.PlanToolRegistry;
import com.lender.assistant.rag.tool.ToolContext;
import com.lender.assistant.rag.tool.ToolErrorCode;
import com.lender.assistant.rag.tool.ToolResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecuteService.class);

    private static final String KB_SEARCH = "kb.search";
    private static final String MARKDOWN_FENCE = "```";
    private static final String STEP_ID_PREFIX = "s";
    private static final int MAX_ATTEMPTS = 2;
    private static final int OUTPUT_SUMMARY_MAX_CHARS = 500;
    private static final int TOOL_POOL_SIZE = 8;
    private static final int TOOL_QUEUE_CAPACITY = 100;
    private static final long TOOL_THREAD_KEEP_ALIVE_SECONDS = 60L;
    private static final String TOOL_THREAD_NAME_PREFIX = "plan-tool-worker";

    private final PlannerModelClient client;
    private final ObjectMapper objectMapper;
    private final PlanToolRegistry registry;
    private final PlanSettings settings;

    /**
     * 工具执行池只用于给同步工具加超时（Future.get）：工具本身不感知线程，
     * 池大小与 SSE 工作线程一致，避免「流线程等工具线程」被自己的池饿死。
     */
    private final ThreadPoolExecutor toolExecutor;

    public PlanAndExecuteService(PlannerModelClient client, ObjectMapper objectMapper,
                                 PlanToolRegistry registry, PlanSettings settings) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.settings = settings;
        this.toolExecutor = ThreadPoolFactory.create(TOOL_POOL_SIZE, TOOL_POOL_SIZE,
                TOOL_QUEUE_CAPACITY, TOOL_THREAD_KEEP_ALIVE_SECONDS, TOOL_THREAD_NAME_PREFIX);
    }

    /** 无知识库目录时的入口（目录只影响提案质量，不参与校验） */
    public ExecutionPlanDTO plan(String question, String previousQuestion) {
        return plan(question, previousQuestion, "");
    }

    public ExecutionPlanDTO plan(String question, String previousQuestion, String knowledgeCatalog) {
        try {
            ModelPayload payload = objectMapper.readValue(
                    cleanModelText(complete(buildPrompt(question, previousQuestion, knowledgeCatalog))),
                    ModelPayload.class);
            return validate(payload);
        } catch (Exception ex) {
            log.warn("计划生成或解析失败，返回无效计划以触发 KNOWLEDGE 降级", ex);
            return ExecutionPlanDTO.invalid();
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
                log.warn("计划模型调用失败（第 {}/{} 次）", attempt + 1, MAX_ATTEMPTS, ex);
            }
        }
        throw lastFailure == null ? new IllegalStateException("计划模型未返回内容") : lastFailure;
    }

    private ExecutionPlanDTO validate(ModelPayload payload) {
        if (payload == null || payload.steps() == null) {
            return ExecutionPlanDTO.invalid();
        }
        int count = payload.steps().size();
        if (count < settings.minSteps() || count > settings.maxSteps()) {
            return ExecutionPlanDTO.invalid();
        }
        List<PlanStepDTO> steps = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            PlanStepDTO step = toStep(payload.steps().get(index), index + 1);
            if (step == null) {
                return ExecutionPlanDTO.invalid();
            }
            steps.add(step);
        }
        return containsKbSearch(steps) ? ExecutionPlanDTO.valid(Objects.toString(payload.goal(), ""), steps)
                : ExecutionPlanDTO.invalid();
    }

    /**
     * 单步校验：order 必须等于位置序号（同时挡住重复 order），
     * stepId 必须等于 s{order}（同时挡住重复 stepId），工具必须命中白名单。
     *
     * @return null 表示该步不合法
     */
    private PlanStepDTO toStep(RawStep raw, int expectedOrder) {
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
        return new PlanStepDTO(raw.stepId(), raw.order(), Objects.toString(raw.title(), ""),
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

    private static boolean containsKbSearch(List<PlanStepDTO> steps) {
        for (PlanStepDTO step : steps) {
            if (KB_SEARCH.equals(step.tool())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 串行执行计划步骤：每步先发 RUNNING，再发终态；单步失败/降级只记录结果，继续后续步骤。
     * 执行阶段不产生 delta——正文只由最后的合成阶段输出。
     *
     * @param plan     已通过整单校验的计划
     * @param topK     kb.search 的默认 topK
     * @param budget   整轮预算；剩余不足合成预留时，剩余步骤直接 SKIPPED
     * @param listener 步骤事件回调（编排层转成 SSE）
     */
    public PlanExecutionResultDTO execute(ExecutionPlanDTO plan, int topK, PlanBudget budget,
                                       StepListener listener) {
        List<StepOutcomeDTO> outcomes = new ArrayList<>();
        boolean budgetExhausted = false;
        for (PlanStepDTO step : plan.steps()) {
            if (!budget.canStartStep(PlanSettings.SYNTHESIS_RESERVE_MILLIS)) {
                budgetExhausted = true;
                outcomes.add(skipped(step));
                listener.onStep(new StepEventDTO(step.stepId(), StepStatus.SKIPPED, 0));
                continue;
            }
            outcomes.add(runStep(step, topK, listener));
        }
        return new PlanExecutionResultDTO(outcomes, budgetExhausted, budget.remainingMillis());
    }

    private StepOutcomeDTO runStep(PlanStepDTO step, int topK, StepListener listener) {
        listener.onStep(new StepEventDTO(step.stepId(), StepStatus.RUNNING, 0));
        long startedAt = System.currentTimeMillis();
        ToolResult result = invoke(step, topK);
        long elapsedMillis = System.currentTimeMillis() - startedAt;
        StepStatus status = statusOf(step, result);
        listener.onStep(new StepEventDTO(step.stepId(), status, elapsedMillis));
        return new StepOutcomeDTO(step.stepId(), step.order(), step.title(), step.tool(), step.args(),
                status, elapsedMillis, result.chunkIds(), result.sources(),
                summaryOf(result.content()), errorCodeOf(status, result));
    }

    /** 工具调用：白名单取缺失 → TOOL_NOT_FOUND；同步工具用 Future.get 施加 TOOL_TIMEOUT */
    private ToolResult invoke(PlanStepDTO step, int topK) {
        Optional<AgentTool> tool = registry.get(step.tool());
        if (tool.isEmpty()) {
            return ToolResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "工具不存在：" + step.tool());
        }
        Future<ToolResult> future = null;
        try {
            future = toolExecutor.submit(() -> tool.get().call(step.args(), new ToolContext(topK)));
            return future.get(settings.toolTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return ToolResult.failure(ToolErrorCode.TOOL_TIMEOUT, "工具执行超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(ToolErrorCode.TOOL_FAILED, "工具执行被中断");
        } catch (ExecutionException ex) {
            return ToolResult.failure(ToolErrorCode.TOOL_FAILED, messageOf(ex.getCause()));
        } catch (RejectedExecutionException ex) {
            return ToolResult.failure(ToolErrorCode.TOOL_FAILED, "工具执行队列已满");
        }
    }

    /** kb.search 零命中是"降级"而非失败：仍算正常结束，只是回答要说明资料缺口 */
    private static StepStatus statusOf(PlanStepDTO step, ToolResult result) {
        if (!result.ok()) {
            return ToolErrorCode.TOOL_DEGRADED.name().equals(result.errorCode())
                    ? StepStatus.DEGRADED : StepStatus.FAILED;
        }
        return KB_SEARCH.equals(step.tool()) && result.chunkIds().isEmpty()
                ? StepStatus.DEGRADED : StepStatus.DONE;
    }

    private static String errorCodeOf(StepStatus status, ToolResult result) {
        if (status == StepStatus.DEGRADED && result.ok()) {
            return ToolErrorCode.TOOL_DEGRADED.name();
        }
        return result.errorCode();
    }

    private static StepOutcomeDTO skipped(PlanStepDTO step) {
        return new StepOutcomeDTO(step.stepId(), step.order(), step.title(), step.tool(), step.args(),
                StepStatus.SKIPPED, 0, List.of(), List.of(), "预算不足，已跳过", null);
    }

    private static String messageOf(Throwable cause) {
        return cause == null || cause.getMessage() == null ? "工具执行失败" : cause.getMessage();
    }

    private static String summaryOf(String content) {
        String text = Objects.toString(content, "");
        return text.length() <= OUTPUT_SUMMARY_MAX_CHARS ? text
                : text.substring(0, OUTPUT_SUMMARY_MAX_CHARS) + "…";
    }

    @PreDestroy
    void shutdownToolExecutor() {
        toolExecutor.shutdownNow();
    }

    private String buildPrompt(String question, String previousQuestion, String knowledgeCatalog) {
        return QaPrompts.planPrompt(settings.minSteps(), settings.maxSteps(), previousQuestion,
                question, knowledgeCatalog,
                String.join(", ", registry.names().stream().sorted().toList()));
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
