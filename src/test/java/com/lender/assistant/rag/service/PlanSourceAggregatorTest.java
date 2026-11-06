package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.dto.StepOutcomeDTO;
import com.lender.assistant.rag.model.dto.StepStatus;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanSourceAggregatorTest {

    @Test
    @DisplayName("跨步重复 chunkId 只保留首次出现，顺序为步骤先后")
    void dedupesAcrossSteps() {
        List<SearchItemResponse> union = PlanSourceAggregator.union(List.of(
                outcome("s1", 1, source(1L, "A"), source(2L, "B")),
                outcome("s2", 2, source(2L, "B"), source(3L, "C"))));

        assertEquals(List.of(1L, 2L, 3L), union.stream().map(SearchItemResponse::chunkId).toList());
        assertEquals("A", union.get(0).content());
    }

    @Test
    @DisplayName("单步多命中按原顺序保留")
    void keepsPerStepOrder() {
        List<SearchItemResponse> union = PlanSourceAggregator.union(List.of(
                outcome("s1", 1, source(9L, "九"), source(7L, "七")),
                outcome("s2", 2)));

        assertEquals(2, union.size());
        assertEquals(9L, union.get(0).chunkId());
        assertEquals(7L, union.get(1).chunkId());
    }

    @Test
    @DisplayName("全部零命中时并集为空，mock 工具不产生来源")
    void emptyWhenNoRecall() {
        List<StepOutcomeDTO> outcomes = List.of(outcome("s1", 1), outcome("s2", 2));
        assertEquals(List.of(), PlanSourceAggregator.union(outcomes));
    }

    private static StepOutcomeDTO outcome(String stepId, int order, SearchItemResponse... sources) {
        List<SearchItemResponse> list = List.of(sources);
        return new StepOutcomeDTO(stepId, order, "步骤", "kb.search", Map.of("q", stepId),
                StepStatus.DONE, 1, list.stream().map(SearchItemResponse::chunkId).toList(),
                list, "摘要", null);
    }

    private static SearchItemResponse source(long chunkId, String content) {
        return new SearchItemResponse(chunkId, "doc-1", "文档", "1.1", content,
                null, null, false, null);
    }
}
