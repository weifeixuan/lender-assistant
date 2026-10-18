package com.lender.assistant.rag.service;

import com.lender.assistant.rag.embedder.EmbeddingClient;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.KeywordHit;
import com.lender.assistant.rag.model.VectorHit;
import com.lender.assistant.rag.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 开工前的测试基线（特征测试）：把阶段 3 调优后的 RRF 行为锁住。
 *
 * 价值：后续阶段 4 任何人改动 SearchService（比如接入工具调用后重排），
 * 这组测试会立刻报警"RRF 融合被改坏了"——阶段 3 那次排序方向写反导致
 * recall 崩到 52.6% 的事故，就是这类测试要拦住的。
 */
class SearchServiceTest {

    private EmbeddingClient embeddingClient;
    private DocChunkMapper mapper;
    private SearchService service;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        mapper = mock(DocChunkMapper.class);
        service = new SearchServiceImpl(embeddingClient, mapper, -1);   // -1 = 相似度阈值关闭，保持本测试锁定的原有 RRF 行为
    }

    private VectorHit vec(long id, String content, double dist) {
        return new VectorHit(id, "doc1", "测试文档", "第1节", content, dist);
    }

    private KeywordHit kw(long id, String content, int hits) {
        return new KeywordHit(id, "doc1", "测试文档", "第1节", content, hits);
    }

    @Test
    @DisplayName("hybrid：RRF 融合排序——双通道都命中的 chunk 排最前")
    void hybridRrfFusion() {
        // 向量通道：A 第1名、B 第2名；关键词通道：B 第1名、C 第2名
        when(embeddingClient.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(eq("[0.0,0.0,0.0,0.0]"), anyInt())).thenReturn(List.of(
                vec(1, "甲内容", 0.10), vec(2, "乙内容", 0.20)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of(
                kw(2, "乙内容", 3), kw(3, "丙内容", 1)));

        List<Map<String, Object>> result = service.search("测试查询", 4, "hybrid");

        // RRF：B = 1/(60+2) + 1/(60+1) = 0.03252 > A = 1/61 = 0.01639 > C = 1/62 = 0.01613
        // （B 在向量通道第2名、关键词通道第1名；A 只有向量第1名；C 只有关键词第2名）
        List<Long> idOrder = result.stream().map(m -> ((Number) m.get("chunkId")).longValue()).toList();
        assertEquals(List.of(2L, 1L, 3L), idOrder, "双通道命中的 B 必须排第一，RRF 分数严格降序");

        assertEquals("0.03252", result.get(0).get("rrfScore"));
        assertEquals("0.01639", result.get(1).get("rrfScore"));
        assertEquals("0.01613", result.get(2).get("rrfScore"));
        // B 双通道命中标记
        assertEquals(true, result.get(0).get("bothChannels"));
        assertEquals(false, result.get(1).get("bothChannels"));
    }

    @Test
    @DisplayName("vector 模式：不调用关键词通道，按距离升序")
    void vectorModeSkipsKeywordChannel() {
        when(embeddingClient.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(eq("[0.0,0.0,0.0,0.0]"), anyInt())).thenReturn(List.of(
                vec(1, "甲", 0.30), vec(2, "乙", 0.10)));

        List<Map<String, Object>> result = service.search("测试查询", 4, "vector");

        verify(mapper, never()).searchByKeyword(any(), anyInt());
        assertEquals(List.of(2L, 1L),
                result.stream().map(m -> ((Number) m.get("chunkId")).longValue()).toList(),
                "距离小的排前面");
    }

    @Test
    @DisplayName("keyword 模式：单字 token 被过滤，只留 2 字以上")
    void keywordModeFiltersSingleCharTokens() {
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of(kw(9, "逾期内容", 2)));

        // "逾期第3列" 分词：逾/逾期/期/期第/第/3/列 —— 过滤后只剩 [逾期, 期第]
        service.search("逾期第3列", 4, "keyword");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper).searchByKeyword(captor.capture(), anyInt());
        assertEquals(List.of("逾期", "期第"), captor.getValue());
    }

    @Test
    @DisplayName("keyword 模式：排序跟随关键词命中排名")
    void keywordModeOrderFollowsRank() {
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of(
                kw(5, "命中第一", 5), kw(6, "命中第二", 1)));

        List<Map<String, Object>> result = service.search("逾期文件", 4, "keyword");

        assertEquals(List.of(5L, 6L),
                result.stream().map(m -> ((Number) m.get("chunkId")).longValue()).toList());
    }

    @Test
    @DisplayName("空 token 查询：关键词通道不做查询（MyBatis <foreach> 空集合守卫），结果仍来自向量通道")
    void emptyTokensSkipKeywordChannel() {
        when(embeddingClient.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(eq("[0.0,0.0,0.0,0.0]"), anyInt())).thenReturn(List.of(vec(1, "甲内容", 0.10)));

        // "3" 分词只剩单字符 token（长度 < 2 被过滤）→ tokens 为空，命中空 token 守卫分支
        List<Map<String, Object>> result = service.search("3", 4, "hybrid");

        verify(mapper, never()).searchByKeyword(any(), anyInt());
        assertEquals(List.of(1L),
                result.stream().map(m -> ((Number) m.get("chunkId")).longValue()).toList(),
                "关键词通道为空不影响向量通道召回");
    }
}
