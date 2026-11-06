package com.lender.assistant.rag.service;

import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.dto.KeywordHitDTO;
import com.lender.assistant.rag.model.dto.VectorHitDTO;
import com.lender.assistant.rag.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

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

    private EmbeddingStrategy embeddingStrategy;
    private DocChunkMapper mapper;
    private SearchService service;

    @BeforeEach
    void setUp() {
        embeddingStrategy = mock(EmbeddingStrategy.class);
        mapper = mock(DocChunkMapper.class);
        service = new SearchServiceImpl(embeddingStrategy, mapper, -1);   // -1 = 相似度阈值关闭，保持本测试锁定的原有 RRF 行为
    }

    /** 缺省切片序号按 id 放大，避免既有用例里的条目被"相邻切片合并"误伤 */
    private VectorHitDTO vec(long id, String content, double dist) {
        return vec(id, (int) (id * 10), "第1节", content, dist);
    }

    private KeywordHitDTO kw(long id, String content, int hits) {
        return kw(id, (int) (id * 10), "第1节", content, hits);
    }

    private VectorHitDTO vec(long id, int chunkIndex, String section, String content, double dist) {
        return new VectorHitDTO(id, "doc1", "测试文档", section, content, dist, chunkIndex);
    }

    private KeywordHitDTO kw(long id, int chunkIndex, String section, String content, int hits) {
        return new KeywordHitDTO(id, "doc1", "测试文档", section, content, hits, chunkIndex);
    }

    @Test
    @DisplayName("hybrid：RRF 融合排序——双通道都命中的 ChunkDTO 排最前")
    void hybridRrfFusion() {
        // 向量通道：A 第1名、B 第2名；关键词通道：B 第1名、C 第2名
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(eq("[0.0,0.0,0.0,0.0]"), anyInt())).thenReturn(List.of(
                vec(1, "甲内容", 0.10), vec(2, "乙内容", 0.20)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of(
                kw(2, "乙内容", 3), kw(3, "丙内容", 1)));

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.HYBRID);

        // RRF：B = 1/(60+2) + 1/(60+1) = 0.03252 > A = 1/61 = 0.01639 > C = 1/62 = 0.01613
        // （B 在向量通道第2名、关键词通道第1名；A 只有向量第1名；C 只有关键词第2名）
        List<Long> idOrder = result.stream().map(SearchItemResponse::chunkId).toList();
        assertEquals(List.of(2L, 1L, 3L), idOrder, "双通道命中的 B 必须排第一，RRF 分数严格降序");

        assertEquals("0.03252", result.get(0).rrfScore());
        assertEquals("0.01639", result.get(1).rrfScore());
        assertEquals("0.01613", result.get(2).rrfScore());
        // B 双通道命中标记
        assertTrue(result.get(0).bothChannels());
        assertFalse(result.get(1).bothChannels());
    }

    @Test
    @DisplayName("vector 模式：不调用关键词通道，按距离升序")
    void vectorModeSkipsKeywordChannel() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(eq("[0.0,0.0,0.0,0.0]"), anyInt())).thenReturn(List.of(
                vec(1, "甲", 0.30), vec(2, "乙", 0.10)));

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.VECTOR);

        verify(mapper, never()).searchByKeyword(any(), anyInt());
        assertEquals(List.of(2L, 1L),
                result.stream().map(SearchItemResponse::chunkId).toList(),
                "距离小的排前面");
    }

    @Test
    @DisplayName("keyword 模式：单字 token 被过滤，只留 2 字以上")
    void keywordModeFiltersSingleCharTokens() {
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of(kw(9, "逾期内容", 2)));

        // "逾期第3列" 分词：逾/逾期/期/期第/第/3/列 —— 过滤后只剩 [逾期, 期第]
        service.search("逾期第3列", 4, SearchMode.KEYWORD);

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

        List<SearchItemResponse> result = service.search("逾期文件", 4, SearchMode.KEYWORD);

        assertEquals(List.of(5L, 6L),
                result.stream().map(SearchItemResponse::chunkId).toList());
    }

    /** 捕获关键词通道收到的 token 列表（追问检索的分段配额断言都看它） */
    @SuppressWarnings("unchecked")
    private List<String> captureKeywordTokens(Runnable call) {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of());
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of());
        call.run();
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper).searchByKeyword(captor.capture(), anyInt());
        return captor.getValue();
    }

    @Test
    @DisplayName("追问关键词配额：上一轮长问题不得挤掉当前问题的 token")
    void keywordQuotaReservesCurrentQuestionTokens() {
        // 上一轮 12 个可用 token 足以吃满 8 个配额；当前追问只有 [响应, 应字, 字段, 段呢]
        List<String> tokens = captureKeywordTokens(() ->
                service.hybridSearch("响应字段呢？", "单笔代收字段请求接口有哪些", 4));

        assertEquals(List.of("响应", "应字", "字段", "段呢", "单笔", "笔代", "代收", "收字"), tokens,
                "当前问题保底 4 个 token 且排在最前，剩余配额才给上一轮");
    }

    @Test
    @DisplayName("追问关键词配额：两段都溢出时当前问题 token 优先")
    void keywordQuotaPrioritizesCurrentWhenBothOverflow() {
        List<String> tokens = captureKeywordTokens(() ->
                service.hybridSearch("响应字段呢请详细说明所有字段含义", "单笔代收字段请求接口有哪些", 4));

        assertEquals(8, tokens.size(), "两段都超配额时总 token 数仍受上限约束");
        assertEquals(List.of("响应", "应字", "字段", "段呢"), tokens.subList(0, 4),
                "配额不足覆盖两段时，当前问题的 token 必须优先进入候选");
    }

    @Test
    @DisplayName("追问关键词配额：当前问题不足 4 个 token 时，余额让给上一轮")
    void keywordQuotaGivesSpareToPreviousWhenCurrentIsShort() {
        // "响应呢？" 只有 [响应, 应呢] 两个可用 token
        List<String> tokens = captureKeywordTokens(() ->
                service.hybridSearch("响应呢？", "单笔代收字段请求接口有哪些", 4));

        assertEquals(8, tokens.size(), "当前问题不足保底值时，余额应补给上一轮而不是空转");
        assertEquals(List.of("响应", "应呢"), tokens.subList(0, 2));
    }

    @Test
    @DisplayName("空 token 查询：关键词通道不做查询（MyBatis <foreach> 空集合守卫），结果仍来自向量通道")
    void emptyTokensSkipKeywordChannel() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(eq("[0.0,0.0,0.0,0.0]"), anyInt())).thenReturn(List.of(vec(1, "甲内容", 0.10)));

        // "3" 分词只剩单字符 token（长度 < 2 被过滤）→ tokens 为空，命中空 token 守卫分支
        List<SearchItemResponse> result = service.search("3", 4, SearchMode.HYBRID);

        verify(mapper, never()).searchByKeyword(any(), anyInt());
        assertEquals(List.of(1L),
                result.stream().map(SearchItemResponse::chunkId).toList(),
                "关键词通道为空不影响向量通道召回");
    }

    @Test
    @DisplayName("候选池放大：两通道按 max(2*topK, 12) 召回，对外仍只返回 topK 条")
    void candidatePoolIsExpanded() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of());
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of());

        service.search("测试查询", 4, SearchMode.HYBRID);
        verify(mapper).searchByVector(any(), eq(12));
        verify(mapper).searchByKeyword(anyList(), eq(12));

        service.search("测试查询", 8, SearchMode.HYBRID);
        verify(mapper).searchByVector(any(), eq(16));
        verify(mapper).searchByKeyword(anyList(), eq(16));
    }

    @Test
    @DisplayName("候选池放大后，对外仍严格截断到 topK 条")
    void expandedPoolStillTruncatesToTopK() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of(
                vec(1, 10, "第1节", "甲", 0.10), vec(2, 20, "第2节", "乙", 0.20),
                vec(3, 30, "第3节", "丙", 0.30), vec(4, 40, "第4节", "丁", 0.40),
                vec(5, 50, "第5节", "戊", 0.50), vec(6, 60, "第6节", "己", 0.60)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of());

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.HYBRID);

        assertEquals(4, result.size(), "放大候选池只影响召回阶段，对外条数仍由 topK 截断");
    }

    @Test
    @DisplayName("同 section 相邻 chunkIndex 合并为一个来源，id 取首片")
    void adjacentChunksAreMerged() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of(
                vec(6, 5, "单笔代付", "代付请求字段与代收接口一致", 0.10),
                vec(4, 3, "单笔代收", "单笔代收请求字段：outBizNo/payerAccount/payeeAccount/amount", 0.30),
                vec(5, 4, "单笔代收", "响应核心字段：hxSeqNo/status", 0.20)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of());

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.HYBRID);

        assertEquals(2, result.size(), "同 section 的 3、4 两片应合并，代付切片独立");
        SearchItemResponse merged = result.stream()
                .filter(item -> item.chunkId() == 4L).findFirst().orElseThrow();
        assertEquals("单笔代收", merged.sectionPath());
        assertTrue(merged.content().contains("outBizNo"), "合并片必须包含字段清单");
        assertTrue(merged.content().contains("hxSeqNo"), "合并片必须包含后续切片的响应字段");
        assertFalse(merged.content().contains("代付请求字段"), "不同 section 的代付切片不得并入");
    }

    @Test
    @DisplayName("同 section 但 chunkIndex 不相邻的切片不合并")
    void nonAdjacentChunksAreNotMerged() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of(
                vec(4, 3, "单笔代收", "字段清单", 0.10),
                vec(5, 5, "单笔代收", "间隔一片的其它内容", 0.20)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of());

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.HYBRID);

        assertEquals(2, result.size(), "chunkIndex 3 与 5 之间缺 4，不得拼成一片");
    }

    @Test
    @DisplayName("合并时去掉切片重叠文本，避免整段被拼接两次")
    void mergedContentDeduplicatesOverlap() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of(
                vec(4, 3, "单笔代收", "字段清单：outBizNo 说明：附言", 0.10),
                vec(5, 4, "单笔代收", "说明：附言 响应字段：hxSeqNo", 0.20)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of());

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.HYBRID);

        assertEquals(1, result.size());
        assertEquals("字段清单：outBizNo 说明：附言 响应字段：hxSeqNo", result.get(0).content(),
                "重叠的「说明：附言」只应出现一次");
    }

    @Test
    @DisplayName("合并片保留成员中最高的 RRF 分数与双通道标记")
    void mergedSourceKeepsBestScore() {
        when(embeddingStrategy.embed(any())).thenReturn(new float[4]);
        when(mapper.searchByVector(any(), anyInt())).thenReturn(List.of(
                vec(4, 3, "单笔代收", "字段清单", 0.01)));
        when(mapper.searchByKeyword(anyList(), anyInt())).thenReturn(List.of(
                kw(5, 4, "单笔代收", "响应字段", 2)));

        List<SearchItemResponse> result = service.search("测试查询", 4, SearchMode.HYBRID);

        assertEquals(1, result.size());
        // 4 在向量通道第 1 名（1/61），5 只命中关键词第 1 名（1/61），合并后取两者较高者
        assertEquals("0.01639", result.get(0).rrfScore());
        assertFalse(result.get(0).bothChannels(), "合并片不因两个单通道成员而变成双通道命中");
    }
}
