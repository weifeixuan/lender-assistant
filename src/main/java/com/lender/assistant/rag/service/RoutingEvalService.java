package com.lender.assistant.rag.service;

import com.lender.assistant.rag.vo.response.RoutingEvalResponse;

/**
 * 路由与计划离线评估：跑固定评估集 → 输出准确率与合法率。
 * 只读当前配置阈值，不在本接口内做阈值调优。
 */
public interface RoutingEvalService {

    RoutingEvalResponse run() throws Exception;
}
