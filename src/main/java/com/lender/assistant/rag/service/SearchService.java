package com.lender.assistant.rag.service;

import java.util.List;
import java.util.Map;

/**
 * 混合检索（向量通道 + 关键词通道）。抽接口的理由：这是 rag 领域对外唯一的检索契约，
 * 后续（阈值调优、重排、缓存）只替换实现，不动调用方。
 */
public interface SearchService {

    List<Map<String, Object>> hybridSearch(String query, int topK);

    /** mode 取值 vector / keyword / hybrid，阶段 3 评估的实验开关 */
    List<Map<String, Object>> search(String query, int topK, String mode);
}
