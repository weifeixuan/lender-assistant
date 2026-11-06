package com.lender.assistant.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.model.dto.ExecutionPlanDTO;
import com.lender.assistant.rag.tool.AgentTool;
import com.lender.assistant.rag.tool.PlanToolRegistry;
import com.lender.assistant.rag.tool.ToolContext;
import com.lender.assistant.rag.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlanAndExecuteServiceTest {

    private static final String VALID_PLAN = """
            {"goal":"华信单笔代收支持的币种，结合 C10001 额度给出建议",
             "steps":[
               {"stepId":"s1","order":1,"title":"检索华信代收支持的币种","tool":"kb.search",
                "args":{"query":"华信银行单笔代收接口 支持的币种"}},
               {"stepId":"s2","order":2,"title":"查询 C10001 额度","tool":"customer.profile",
                "args":{"customerId":"C10001"}}
             ]}
            """;

    @Test
    @DisplayName("合法计划保留 goal 与顺序，不降级")
    void keepsValidPlan() {
        ExecutionPlanDTO plan = service(prompt -> VALID_PLAN).plan(QUESTION, PREVIOUS);

        assertFalse(plan.degraded());
        assertEquals(2, plan.steps().size());
        assertEquals("s1", plan.steps().get(0).stepId());
        assertTrue(plan.goal().contains("华信"));
    }

    @Test
    @DisplayName("只有 1 步判 PLAN_INVALID 并清空步骤")
    void rejectsTooFewSteps() {
        ExecutionPlanDTO plan = service(prompt -> """
                {"goal":"只查一件事","steps":[
                  {"stepId":"s1","order":1,"title":"查币种","tool":"kb.search",
                   "args":{"query":"华信币种"}}]}
                """).plan(QUESTION, PREVIOUS);

        assertTrue(plan.degraded());
        assertEquals("PLAN_INVALID", plan.degradedReason());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    @DisplayName("超过 5 步判 PLAN_INVALID")
    void rejectsTooManySteps() {
        ExecutionPlanDTO plan = service(prompt -> sixStepPlan()).plan(QUESTION, PREVIOUS);

        assertTrue(plan.degraded());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    @DisplayName("白名单外工具判 PLAN_INVALID")
    void rejectsUnknownTool() {
        ExecutionPlanDTO plan = service(prompt -> """
                {"goal":"两步","steps":[
                  {"stepId":"s1","order":1,"title":"查币种","tool":"kb.search",
                   "args":{"query":"华信币种"}},
                  {"stepId":"s2","order":2,"title":"调外部接口","tool":"external.api",
                   "args":{"id":"1"}}
                ]}
                """).plan(QUESTION, PREVIOUS);

        assertTrue(plan.degraded());
    }

    @Test
    @DisplayName("缺少 kb.search 判降级")
    void rejectsPlanWithoutKbSearch() {
        ExecutionPlanDTO plan = service(prompt -> """
                {"goal":"两步","steps":[
                  {"stepId":"s1","order":1,"title":"查客户","tool":"customer.profile",
                   "args":{"customerId":"C10001"}},
                  {"stepId":"s2","order":2,"title":"查产品","tool":"lender.product",
                   "args":{"productCode":"P001"}}
                ]}
                """).plan(QUESTION, PREVIOUS);

        assertTrue(plan.degraded());
    }

    @Test
    @DisplayName("stepId 与 order 乱序或重复判降级")
    void rejectsBrokenOrdering() {
        ExecutionPlanDTO plan = service(prompt -> """
                {"goal":"两步","steps":[
                  {"stepId":"s1","order":2,"title":"查币种","tool":"kb.search",
                   "args":{"query":"华信币种"}},
                  {"stepId":"s2","order":2,"title":"再查","tool":"kb.search",
                   "args":{"query":"华信额度"}}
                ]}
                """).plan(QUESTION, PREVIOUS);

        assertTrue(plan.degraded());
    }

    @Test
    @DisplayName("非法 JSON 判降级不抛异常")
    void invalidJsonDegrades() {
        ExecutionPlanDTO plan = service(prompt -> "not json").plan(QUESTION, PREVIOUS);

        assertTrue(plan.degraded());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    @DisplayName("Prompt 分块：上一轮仅解析指代，当前问题为本轮目标，步数 2–5")
    void promptSeparatesContext() {
        AtomicReference<String> captured = new AtomicReference<>();
        service(prompt -> {
            captured.set(prompt);
            return VALID_PLAN;
        }).plan(QUESTION, PREVIOUS);

        assertNotNull(captured.get());
        assertTrue(captured.get().contains("仅用于解析指代"));
        assertTrue(captured.get().contains("本轮目标"));
        assertTrue(captured.get().contains("2–5"));
    }

    private static final String QUESTION = "华信单笔代收支持哪些币种？客户 C10001 的额度还剩多少？";
    private static final String PREVIOUS = "恒昌代收接口的限额是多少";

    private PlanAndExecuteService service(com.lender.assistant.rag.client.PlannerModelClient client) {
        PlanToolRegistry registry = new PlanToolRegistry(List.<AgentTool>of(
                fakeTool("kb.search"), fakeTool("customer.profile"), fakeTool("lender.product")));
        return new PlanAndExecuteService(client, new ObjectMapper(), registry,
                new PlanSettings(2, 5, PlanSettings.TOOL_TIMEOUT_MILLIS));
    }

    private static AgentTool fakeTool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult call(Map<String, Object> args, ToolContext context) {
                return ToolResult.success("", List.of());
            }
        };
    }

    private static String sixStepPlan() {
        StringBuilder json = new StringBuilder("{\"goal\":\"六步\",\"steps\":[");
        for (int i = 1; i <= 6; i++) {
            if (i > 1) {
                json.append(',');
            }
            json.append("{\"stepId\":\"s").append(i).append("\",\"order\":").append(i)
                    .append(",\"title\":\"查").append(i).append("\",\"tool\":\"kb.search\",")
                    .append("\"args\":{\"query\":\"查询").append(i).append("\"}}");
        }
        return json.append("]}").toString();
    }
}
