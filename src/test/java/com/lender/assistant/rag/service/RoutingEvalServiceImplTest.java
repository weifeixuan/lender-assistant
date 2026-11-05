package com.lender.assistant.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.plan.PlanAndExecuteService;
import com.lender.assistant.rag.plan.model.ExecutionPlan;
import com.lender.assistant.rag.plan.model.PlanStep;
import com.lender.assistant.rag.routing.QuestionRoutingService;
import com.lender.assistant.rag.routing.model.QuestionRoute;
import com.lender.assistant.rag.routing.model.RoutingDecision;
import com.lender.assistant.rag.service.impl.RoutingEvalServiceImpl;
import com.lender.assistant.rag.vo.response.RoutingEvalResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingEvalServiceImplTest {

    private static final String TEST_SET = "eval/routing-evalset-test.json";

    @Test
    @DisplayName("固定评估集：按标注统计路由准确率、计划合法率与指代验收")
    void computesMetrics() throws Exception {
        QuestionRoutingService routing = mock(QuestionRoutingService.class);
        when(routing.route(any())).thenReturn(
                new RoutingDecision(QuestionRoute.KNOWLEDGE, 0.9, "单事实", "NONE"));
        PlanAndExecuteService planning = mock(PlanAndExecuteService.class);
        when(planning.plan(any(), any())).thenReturn(validPlan());

        RoutingEvalResponse response = service(routing, planning).run();

        assertEquals(4, response.total());
        assertEquals(0.5, response.routeAccuracy());
        assertEquals(0.0, response.lowConfidenceRatio());
        assertEquals(2, response.planItems());
        assertEquals(1.0, response.planValidRate());
        assertEquals(2.0, response.avgSteps());
        assertEquals(1, response.coreferenceItems());
        assertEquals(0.0, response.coreferencePassRate(), "计划含 customer.profile，超出允许清单");
        assertTrue(response.markdown().contains("路由准确率"));
        assertTrue(response.markdown().contains("不提供校准结论"));
    }

    @Test
    @DisplayName("mock 计划恒弃用：计划合法率为 0，不做任何伪造")
    void mockPlannerYieldsZeroPlanValidity() throws Exception {
        QuestionRoutingService routing = mock(QuestionRoutingService.class);
        when(routing.route(any())).thenReturn(
                new RoutingDecision(QuestionRoute.KNOWLEDGE, 0.4, "降级", "NONE"));
        PlanAndExecuteService planning = mock(PlanAndExecuteService.class);
        when(planning.plan(any(), any())).thenReturn(ExecutionPlan.invalid());

        RoutingEvalResponse response = service(routing, planning).run();

        assertEquals(0.0, response.planValidRate());
        assertEquals(0.0, response.avgSteps());
        assertEquals(1.0, response.lowConfidenceRatio(), "4 条置信度 0.4 全部低于 0.6");
    }

    private static RoutingEvalServiceImpl service(QuestionRoutingService routing,
                                                  PlanAndExecuteService planning) {
        RoutingEvalServiceImpl service = new RoutingEvalServiceImpl(routing, planning,
                new ObjectMapper(), TEST_SET);
        ReflectionTestUtils.setField(service, "threshold", 0.6);
        return service;
    }

    private static ExecutionPlan validPlan() {
        return ExecutionPlan.valid("目标", List.of(
                new PlanStep("s1", 1, "检索", "kb.search", Map.of("query", "放款条件")),
                new PlanStep("s2", 2, "查客户", "customer.profile", Map.of("customerId", "C10001"))));
    }
}
