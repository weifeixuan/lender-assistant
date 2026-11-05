package com.lender.assistant.rag.routing.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.routing.QuestionRoutingService;
import com.lender.assistant.rag.routing.RoutingModelClient;
import com.lender.assistant.rag.routing.model.QuestionRoute;
import com.lender.assistant.rag.routing.model.RoutingDecision;
import com.lender.assistant.rag.routing.model.RoutingRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 路由判定：模型只负责「说」，降级规则由代码兜底。
 * 任何异常路径都返回 KNOWLEDGE（fail-open），路由失败不会变成用户侧失败。
 */
@Service
public class QuestionRoutingServiceImpl implements QuestionRoutingService {

    private static final String CATEGORY_NONE = "NONE";
    private static final String FALLBACK_REASON = "路由失败，降级知识库";
    private static final String MARKDOWN_FENCE = "```";

    private final RoutingModelClient client;
    private final ObjectMapper objectMapper;
    private final double confidenceThreshold;

    public QuestionRoutingServiceImpl(RoutingModelClient client, ObjectMapper objectMapper,
                                      @Value("${rag.routing.confidence-threshold:0.6}")
                                      double confidenceThreshold) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public RoutingDecision route(RoutingRequest request) {
        try {
            ModelPayload payload = objectMapper.readValue(
                    cleanModelText(client.complete(buildPrompt(request))), ModelPayload.class);
            return decide(payload, request.previousRoute());
        } catch (Exception ex) {
            return fallback();
        }
    }

    private RoutingDecision decide(ModelPayload payload, QuestionRoute previousRoute) {
        QuestionRoute route = QuestionRoute.from(payload.route());
        if (route == null || payload.confidence() == null
                || payload.confidence() < confidenceThreshold) {
            return fallback();
        }
        if (route == QuestionRoute.IRRELEVANT
                && (previousRoute == QuestionRoute.KNOWLEDGE || previousRoute == QuestionRoute.PLAN)) {
            return new RoutingDecision(QuestionRoute.KNOWLEDGE, payload.confidence(),
                    "追问保护：上一轮为 " + previousRoute.name(), CATEGORY_NONE);
        }
        String category = route == QuestionRoute.IRRELEVANT && payload.irrelevantCategory() != null
                ? payload.irrelevantCategory() : CATEGORY_NONE;
        return new RoutingDecision(route, payload.confidence(),
                Objects.toString(payload.reason(), ""), category);
    }

    private RoutingDecision fallback() {
        return new RoutingDecision(QuestionRoute.KNOWLEDGE, 0.0, FALLBACK_REASON, CATEGORY_NONE);
    }

    private String buildPrompt(RoutingRequest request) {
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
               """.formatted(nullToEmpty(request.previousQuestion()), request.question(),
                request.previousRoute() == null ? "无" : request.previousRoute().name(),
                nullToEmpty(request.knowledgeCatalog()));
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
    private record ModelPayload(String route, Double confidence, String reason,
                                String irrelevantCategory) {
    }
}
