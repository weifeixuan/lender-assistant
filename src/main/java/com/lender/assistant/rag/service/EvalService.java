package com.lender.assistant.rag.service;

import java.util.Map;

/**
 * 检索质量评估（跑评估集 → 算 recall@K / MRR → 出 markdown 报告）。
 * 评估集资源固定在 resources/eval/evalset.json，不随本次迁移移动。
 */
public interface EvalService {

    Map<String, Object> run(int topK, String mode) throws Exception;
}
