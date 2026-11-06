package com.lender.assistant.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.client.RoutingModelClient;
import com.lender.assistant.rag.service.QuestionRoutingService;
import com.lender.assistant.rag.service.impl.QuestionRoutingServiceImpl;
import com.lender.assistant.rag.model.dto.QuestionRoute;
import com.lender.assistant.rag.model.dto.RoutingDecisionDTO;
import com.lender.assistant.rag.model.dto.RoutingRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由服务的行为测试：只通过 RoutingModelClient 这个缝喂入模型输出，
 * 断言「分类结果」与「降级方向」，不关心 prompt 内部实现。
 */
class QuestionRoutingServiceImplTest {

    private static final double THRESHOLD = 0.6;

    @Test
    @DisplayName("合法 JSON 且高置信时按模型判定")
    void keepsHighConfidenceRoute() {
        RoutingDecisionDTO decision = service("""
                {"route":"PLAN","confidence":0.82,"reason":"跨文档比较",
                 "irrelevantCategory":"NONE"}
                """).route(request("华信和恒昌的代收接口有什么区别", null, null));

        assertEquals(QuestionRoute.PLAN, decision.route());
        assertEquals(0.82, decision.confidence(), 0.0001);
    }

    @Test
    @DisplayName("非法 JSON 降级 KNOWLEDGE 且不抛异常")
    void invalidJsonFallsBack() {
        RoutingDecisionDTO decision = service("这不是 JSON")
                .route(request("华信单笔代收支持哪些币种", null, null));

        assertEquals(QuestionRoute.KNOWLEDGE, decision.route());
    }

    @Test
    @DisplayName("未知路由值降级 KNOWLEDGE")
    void unknownRouteFallsBack() {
        RoutingDecisionDTO decision = service("""
                {"route":"COMPLEX","confidence":0.9,"reason":"旧分类",
                 "irrelevantCategory":"NONE"}
                """).route(request("华信和恒昌的代收接口有什么区别", null, null));

        assertEquals(QuestionRoute.KNOWLEDGE, decision.route());
    }

    @Test
    @DisplayName("置信度低于阈值降级 KNOWLEDGE")
    void lowConfidenceFallsBack() {
        RoutingDecisionDTO decision = service("""
                {"route":"PLAN","confidence":0.5,"reason":"不确定",
                 "irrelevantCategory":"NONE"}
                """).route(request("华信和恒昌的代收接口有什么区别", null, null));

        assertEquals(QuestionRoute.KNOWLEDGE, decision.route());
    }

    @Test
    @DisplayName("上一轮是知识库问题时，越权类无关被追问保护拉回 KNOWLEDGE")
    void followUpProtectionFromKnowledge() {
        RoutingDecisionDTO decision = service("""
                {"route":"IRRELEVANT","confidence":0.9,"reason":"要求导出他人数据",
                 "irrelevantCategory":"UNAUTHORIZED"}
                """).route(request("把刚才那个客户的合同导出来", "华信代收接口支持哪些币种",
                QuestionRoute.KNOWLEDGE));

        assertEquals(QuestionRoute.KNOWLEDGE, decision.route());
    }

    @Test
    @DisplayName("上一轮是计划问题时，越界评价类无关同样受追问保护")
    void followUpProtectionFromPlan() {
        RoutingDecisionDTO decision = service("""
                {"route":"IRRELEVANT","confidence":0.9,"reason":"评价资方好坏",
                 "irrelevantCategory":"OUT_OF_SCOPE"}
                """).route(request("刚才那两家资方哪个更好用", "比较华信和恒昌的代收接口",
                QuestionRoute.PLAN));

        assertEquals(QuestionRoute.KNOWLEDGE, decision.route());
    }

    @Test
    @DisplayName("模型未给分类（NONE）的高置信无关不受追问保护，避免把闲聊拖进检索")
    void followUpUnknownCategoryHonorsIrrelevant() {
        RoutingDecisionDTO decision = service("""
                {"route":"IRRELEVANT","confidence":0.99,"reason":"当前问题是问候和天气闲聊",
                 "irrelevantCategory":"NONE"}
                """).route(request("你好，今天天气怎么样", "华信代收接口支持哪些币种",
                QuestionRoute.KNOWLEDGE));

        assertEquals(QuestionRoute.IRRELEVANT, decision.route());
        assertEquals("NONE", decision.irrelevantCategory());
    }

    @Test
    @DisplayName("无上一轮的纯问候仍判无关")
    void greetingStaysIrrelevant() {
        RoutingDecisionDTO decision = service("""
                {"route":"IRRELEVANT","confidence":0.95,"reason":"问候",
                 "irrelevantCategory":"SMALL_TALK"}
                """).route(request("你好", null, null));

        assertEquals(QuestionRoute.IRRELEVANT, decision.route());
        assertEquals("SMALL_TALK", decision.irrelevantCategory());
    }

    @Test
    @DisplayName("上一轮是知识库问题，但本轮是高置信闲聊时仍判无关")
    void followUpSmallTalkStaysIrrelevant() {
        RoutingDecisionDTO decision = service("""
                {"route":"IRRELEVANT","confidence":0.99,"reason":"问候和天气闲聊",
                 "irrelevantCategory":"SMALL_TALK"}
                """).route(request("你好，今天天气怎么样", "华信代收接口的请求字段",
                QuestionRoute.KNOWLEDGE));

        assertEquals(QuestionRoute.IRRELEVANT, decision.route());
        assertEquals("SMALL_TALK", decision.irrelevantCategory());
    }

    @Test
    @DisplayName("模型返回 GREETING_CHITCHAT 这类闲聊分类时同样不受追问保护")
    void followUpChitchatStaysIrrelevant() {
        RoutingDecisionDTO decision = service("""
                {"route":"IRRELEVANT","confidence":0.95,"reason":"问候和天气闲聊",
                 "irrelevantCategory":"GREETING_CHITCHAT"}
                """).route(request("你好，今天天气怎么样", "比较华信和恒昌的代收接口",
                QuestionRoute.PLAN));

        assertEquals(QuestionRoute.IRRELEVANT, decision.route());
        assertEquals("GREETING_CHITCHAT", decision.irrelevantCategory());
    }

    @Test
    @DisplayName("Prompt 明确分块：上一轮只用于解析指代，当前问题才是本轮目标")
    void promptSeparatesPreviousAndCurrentQuestion() {
        AtomicReference<String> prompt = new AtomicReference<>();
        RoutingModelClient client = text -> {
            prompt.set(text);
            return "{\"route\":\"KNOWLEDGE\",\"confidence\":0.9}";
        };
        service(client).route(request("那二套呢", "华信代收支持哪些币种", null));

        assertNotNull(prompt.get());
        assertTrue(prompt.get().contains("仅用于解析指代"), "缺少上一轮用途限定");
        assertTrue(prompt.get().contains("本轮目标"), "缺少当前问题定位");
        assertTrue(prompt.get().contains("忽略上一轮问题"), "缺少忽略规则");
    }

    private QuestionRoutingService service(String modelOutput) {
        return service(prompt -> modelOutput);
    }

    private QuestionRoutingService service(RoutingModelClient client) {
        return new QuestionRoutingServiceImpl(client, new ObjectMapper(), THRESHOLD);
    }

    private static RoutingRequestDTO request(String question, String previousQuestion,
                                          QuestionRoute previousRoute) {
        return new RoutingRequestDTO(question, previousQuestion, previousRoute, "");
    }
}
