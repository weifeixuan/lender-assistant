package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.dto.RoutingDecisionDTO;
import com.lender.assistant.rag.model.dto.RoutingRequestDTO;

/** 每轮一次的问题路由判定；任何失败都降级 KNOWLEDGE，绝不抛异常。 */
public interface QuestionRoutingService {

    RoutingDecisionDTO route(RoutingRequestDTO request);
}
