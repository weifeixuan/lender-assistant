package com.lender.assistant.rag.service;

import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import java.util.List;

/**
 * 混合检索（向量通道 + 关键词通道）。抽接口的理由：这是 rag 领域对外唯一的检索契约，
 * 后续（阈值调优、重排、缓存）只替换实现，不动调用方。
 */
public interface SearchService {

    List<SearchItemResponse> hybridSearch(String query, int topK);

    /** 检索模式见 {@link SearchMode}，阶段 3 评估的实验开关 */
    List<SearchItemResponse> search(String query, int topK, SearchMode mode);
}
