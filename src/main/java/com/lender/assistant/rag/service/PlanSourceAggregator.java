package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.dto.StepOutcomeDTO;
import com.lender.assistant.rag.model.response.SearchItemResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多步来源并集：按 chunkId 去重，顺序为「步骤 order + 单步内排名」。
 * 调用方再按并集顺序从 1 连续编号，即可保证全局 idx 无空洞。
 */
public final class PlanSourceAggregator {

    private PlanSourceAggregator() {
    }

    public static List<SearchItemResponse> union(List<StepOutcomeDTO> outcomes) {
        Map<Long, SearchItemResponse> unique = new LinkedHashMap<>();
        for (StepOutcomeDTO outcome : outcomes) {
            for (SearchItemResponse source : outcome.sources()) {
                unique.putIfAbsent(source.chunkId(), source);
            }
        }
        return List.copyOf(unique.values());
    }

    /** 并集顺序 → 全局引用编号（从 1 起），与 SSE sources 事件的 idx 同一口径 */
    public static Map<Long, Integer> globalIndexes(List<SearchItemResponse> union) {
        Map<Long, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < union.size(); i++) {
            indexes.put(union.get(i).chunkId(), i + 1);
        }
        return indexes;
    }
}
