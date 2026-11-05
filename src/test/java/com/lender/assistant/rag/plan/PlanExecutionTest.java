package com.lender.assistant.rag.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.plan.model.ExecutionPlan;
import com.lender.assistant.rag.plan.model.PlanExecutionResult;
import com.lender.assistant.rag.plan.model.PlanStep;
import com.lender.assistant.rag.plan.model.StepEvent;
import com.lender.assistant.rag.plan.model.StepOutcome;
import com.lender.assistant.rag.plan.model.StepStatus;
import com.lender.assistant.rag.tool.AgentTool;
import com.lender.assistant.rag.tool.PlanToolRegistry;
import com.lender.assistant.rag.tool.ToolContext;
import com.lender.assistant.rag.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionTest {

    private static final int TOP_K = 5;
    private static final long SHORT_TIMEOUT_MILLIS = 50L;

    @Test
    @DisplayName("每步先发 RUNNING 再发终态，正常步骤为 DONE")
    void emitsRunningThenDone() {
        List<StepEvent> events = new ArrayList<>();
        PlanExecutionResult result = service(kbTool(List.of(11L, 12L)), profileTool())
                .execute(twoStepPlan(), TOP_K, budget(), events::add);

        assertEquals(4, events.size());
        assertEquals(new StepEvent("s1", StepStatus.RUNNING, 0), events.get(0));
        assertEquals(new StepEvent("s2", StepStatus.RUNNING, 0), events.get(2));
        assertEquals(StepStatus.DONE, events.get(1).status());
        assertEquals(StepStatus.DONE, events.get(3).status());
        assertEquals(List.of(StepStatus.DONE, StepStatus.DONE), statuses(result));
    }

    @Test
    @DisplayName("单步异常只记 FAILED，后续步骤继续执行")
    void continuesAfterStepFailure() {
        PlanExecutionResult result = service(new ThrowingTool("kb.search"), profileTool())
                .execute(twoStepPlan(), TOP_K, budget(), event -> {
                });

        assertEquals(List.of(StepStatus.FAILED, StepStatus.DONE), statuses(result));
        assertEquals("TOOL_FAILED", result.steps().get(0).errorCode());
        assertEquals(List.of(), result.steps().get(0).sourceChunkIds());
    }

    @Test
    @DisplayName("白名单外工具记 FAILED / TOOL_NOT_FOUND，不抛异常")
    void unknownToolFails() {
        ExecutionPlan plan = ExecutionPlan.valid("目标", List.of(
                new PlanStep("s1", 1, "查资料", "kb.search", Map.of("query", "华信币种")),
                new PlanStep("s2", 2, "调外部接口", "external.api", Map.of("id", "1"))));

        PlanExecutionResult result = service(kbTool(List.of(11L)), profileTool())
                .execute(plan, TOP_K, budget(), event -> {
                });

        assertEquals(StepStatus.FAILED, result.steps().get(1).status());
        assertEquals("TOOL_NOT_FOUND", result.steps().get(1).errorCode());
    }

    @Test
    @DisplayName("kb.search 零召回记 DEGRADED / TOOL_DEGRADED")
    void zeroRecallDegrades() {
        PlanExecutionResult result = service(kbTool(List.of()), profileTool())
                .execute(twoStepPlan(), TOP_K, budget(), event -> {
                });

        StepOutcome first = result.steps().get(0);
        assertEquals(StepStatus.DEGRADED, first.status());
        assertEquals("TOOL_DEGRADED", first.errorCode());
        assertEquals(StepStatus.DONE, result.steps().get(1).status());
    }

    @Test
    @DisplayName("工具超时记 FAILED / TOOL_TIMEOUT，后续步骤继续")
    void toolTimeoutFails() {
        PlanExecutionResult result = service(new SlowTool("kb.search", 300), profileTool())
                .execute(twoStepPlan(), TOP_K, budget(), event -> {
                });

        assertEquals(StepStatus.FAILED, result.steps().get(0).status());
        assertEquals("TOOL_TIMEOUT", result.steps().get(0).errorCode());
        assertEquals(StepStatus.DONE, result.steps().get(1).status());
    }

    @Test
    @DisplayName("剩余预算不足合成预留时，未开始步骤全部 SKIPPED")
    void skipsStepsWhenBudgetLow() {
        AtomicLong now = new AtomicLong(0);
        PlanBudget budget = new PlanBudget(90_000, now::get);
        now.set(80_000);
        List<StepEvent> events = new ArrayList<>();

        PlanExecutionResult result = service(kbTool(List.of(11L)), profileTool())
                .execute(twoStepPlan(), TOP_K, budget, events::add);

        assertTrue(result.budgetExhausted());
        assertEquals(List.of(StepStatus.SKIPPED, StepStatus.SKIPPED), statuses(result));
        assertEquals(List.of(StepStatus.SKIPPED, StepStatus.SKIPPED),
                events.stream().map(StepEvent::status).toList());
        assertTrue(result.remainingBudgetMillis() < PlanSettings.SYNTHESIS_RESERVE_MILLIS);
    }

    @Test
    @DisplayName("预算充足时不标记预算耗尽")
    void doesNotMarkBudgetExhaustedWhenEnough() {
        PlanExecutionResult result = service(kbTool(List.of(11L)), profileTool())
                .execute(twoStepPlan(), TOP_K, budget(), event -> {
                });

        assertFalse(result.budgetExhausted());
    }

    private static PlanBudget budget() {
        return new PlanBudget(90_000, System::currentTimeMillis);
    }

    private static List<StepStatus> statuses(PlanExecutionResult result) {
        return result.steps().stream().map(StepOutcome::status).toList();
    }

    private static ExecutionPlan twoStepPlan() {
        return ExecutionPlan.valid("华信币种与额度", List.of(
                new PlanStep("s1", 1, "检索华信币种", "kb.search", Map.of("query", "华信币种")),
                new PlanStep("s2", 2, "查询客户额度", "customer.profile",
                        Map.of("customerId", "C10001"))));
    }

    private static PlanAndExecuteService service(AgentTool... tools) {
        PlanToolRegistry registry = new PlanToolRegistry(List.of(tools));
        return new PlanAndExecuteService(prompt -> "{}", new ObjectMapper(), registry,
                new PlanSettings(2, 5, SHORT_TIMEOUT_MILLIS));
    }

    private static AgentTool kbTool(List<Long> chunkIds) {
        return new FixedTool("kb.search", ToolResult.success("命中内容", chunkIds));
    }

    private static AgentTool profileTool() {
        return new FixedTool("customer.profile", ToolResult.success("客户摘要", List.of()));
    }

    private record FixedTool(String name, ToolResult result) implements AgentTool {

        @Override
        public ToolResult call(Map<String, Object> args, ToolContext context) {
            return result;
        }
    }

    private record ThrowingTool(String name) implements AgentTool {

        @Override
        public ToolResult call(Map<String, Object> args, ToolContext context) {
            throw new IllegalStateException("工具内部故障");
        }
    }

    private record SlowTool(String name, long sleepMillis) implements AgentTool {

        @Override
        public ToolResult call(Map<String, Object> args, ToolContext context) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("慢工具", List.of(1L));
        }
    }
}
