package com.lender.assistant.rag.routing;

import com.lender.assistant.rag.routing.model.RoutingDecision;
import com.lender.assistant.rag.routing.model.RoutingRequest;

/** 每轮一次的问题路由判定；任何失败都降级 KNOWLEDGE，绝不抛异常。 */
public interface QuestionRoutingService {

    RoutingDecision route(RoutingRequest request);
}
