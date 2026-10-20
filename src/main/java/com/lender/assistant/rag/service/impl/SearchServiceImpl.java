package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.common.util.PgVectorUtils;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.KeywordHit;
import com.lender.assistant.rag.model.VectorHit;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.strategy.impl.HashEmbeddingStrategy;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合检索：向量通道 + 关键词通道，结果合并去重。
 *
 * 为什么两条通道都要（面试高频问题）：
 * - 向量通道管"语义相近"：「还款计划文件里逾期的部分」能召回没出现"逾期"原词的段落；
 * - 关键词通道管"字面必须命中"：「逾期文件第3列」这种精确列号问题，
 *   Mock/真向量都可能把"3 列"和语义无关的文本算出噪声相似度，字面匹配最可靠；
 * - 合并策略 v1 很朴素：向量按距离排序、关键词按相似度排序，
 *   同一 chunk 以"两通道都命中"优先（说明强相关）。
 *   权重融合（RRF 等）是阶段 3 调优的内容。
 */
@Service
public class SearchServiceImpl implements SearchService {

    /** RRF 融合平滑常数：score = Σ 1/(K + rank)，60 为论文常用值（越大越不偏袒单通道的头部排名） */
    private static final int RRF_SMOOTHING_K = 60;
    /** 关键词 token 最短长度：单字 LIKE 命中面太宽（"的""是"满库都是），只留 2 字以上 */
    private static final int KEYWORD_TOKEN_MIN_CHARS = 2;
    /** 关键词 token 去重后上限：防止 <foreach> 拼出的 SQL 过长 */
    private static final int KEYWORD_TOKEN_MAX_COUNT = 8;
    /** 关键词通道排序的缺省排名：未命中关键词通道的 chunk 排到末尾 */
    private static final int RANK_FALLBACK = 99;
    /** 向量通道排序的缺省距离：仅关键词命中（无 vectorDistance）时排到末尾 */
    private static final double DISTANCE_FALLBACK = 2;

    private final EmbeddingStrategy embeddingStrategy;
    private final DocChunkMapper docChunkMapper;

    /**
     * 向量通道相似度阈值：余弦距离大于该值的召回被丢弃，用于挡住"完全不相关"的命中。
     * 默认 -1 = 关闭（不改变现有召回行为）——是否开启由阶段 3 评估数据决定，
     * 故此处只提供开关而非直接改默认语义。
     */
    private final double maxDistance;

    public SearchServiceImpl(EmbeddingStrategy embeddingStrategy, DocChunkMapper docChunkMapper,
                             @Value("${rag.search.max-distance:-1}") double maxDistance) {
        this.embeddingStrategy = embeddingStrategy;
        this.docChunkMapper = docChunkMapper;
        this.maxDistance = maxDistance;
    }

    @Override
    public List<SearchItemResponse> hybridSearch(String query, int topK) {
        return search(query, topK, SearchMode.HYBRID);
    }

    /**
     * mode 参数是阶段 3 评估的实验开关：
     * - vector  只走向量通道：量化"语义召回"的单独贡献
     * - keyword 只走关键词通道：量化"字面召回"的单独贡献
     * - hybrid  双通道合并（生产路径）
     * 三模式跑同一评估集的对比，就是简历上"混合检索比单通道召回率提升 XX%"数据的来源。
     */
    @Override
    public List<SearchItemResponse> search(String query, int topK, SearchMode mode) {
        // 每条 chunk 记录它在各通道内的排名（1-based），hybrid 模式用 RRF 融合
        Map<Long, SearchItemResponse> merged = new LinkedHashMap<>();
        Map<Long, Integer> vectorRank = new LinkedHashMap<>();
        Map<Long, Integer> keywordRank = new LinkedHashMap<>();

        // 通道 1：向量召回
        if (mode != SearchMode.KEYWORD) {
            float[] queryVec = embeddingStrategy.embed(query);
            List<VectorHit> hits = docChunkMapper.searchByVector(PgVectorUtils.toLiteral(queryVec), topK);
            int rank = 0;
            for (VectorHit hit : hits) {
                if (maxDistance >= 0 && hit.distance() > maxDistance) {
                    continue;   // 超出阈值的召回丢弃（maxDistance<0 时该开关关闭）
                }
                rank++;
                merged.put(hit.id(), new SearchItemResponse(hit.id(), hit.docId(), hit.docTitle(),
                        hit.sectionPath(), hit.content(), hit.distance(), null, false, null));
                vectorRank.put(hit.id(), rank);
            }
        }

        // 通道 2：关键词召回——分词后多词 OR 匹配（整句算相似度会被长句稀释基本不命中，拆词才有效）
        // 过滤：单字 token 命中面太宽，只留 2 字以上；去重后限量防 SQL 过长
        if (mode != SearchMode.VECTOR) {
            List<String> tokens = HashEmbeddingStrategy.tokenize(query).stream()
                    .filter(t -> t.length() >= KEYWORD_TOKEN_MIN_CHARS)
                    .distinct()
                    .limit(KEYWORD_TOKEN_MAX_COUNT)
                    .toList();
            // 空 token 守卫下沉到 Service：MyBatis <foreach> 空集合会生成非法 SQL
            //（旧 DocumentStore 在方法内部处理 tokens.isEmpty()）
            List<KeywordHit> hits = tokens.isEmpty() ? List.of() : docChunkMapper.searchByKeyword(tokens, topK);
            for (int i = 0; i < hits.size(); i++) {
                KeywordHit hit = hits.get(i);
                keywordRank.put(hit.id(), i + 1);
                SearchItemResponse existing = merged.get(hit.id());
                if (existing != null) {
                    // 双通道都命中：保留向量侧信息，补齐关键词命中数并置 bothChannels=true
                    merged.put(hit.id(), new SearchItemResponse(existing.chunkId(), existing.docId(),
                            existing.docTitle(), existing.sectionPath(), existing.content(),
                            existing.vectorDistance(), hit.hits(), true, existing.rrfScore()));
                } else {
                    merged.put(hit.id(), new SearchItemResponse(hit.id(), hit.docId(), hit.docTitle(),
                            hit.sectionPath(), hit.content(), null, hit.hits(), false, null));
                }
            }
        }

        List<SearchItemResponse> result = fuse(mode, new ArrayList<>(merged.values()), vectorRank, keywordRank);
        return result.subList(0, Math.min(topK, result.size()));
    }

    /**
     * 融合排序：hybrid 用 RRF 按两路排名融合（并写回 rrfScore 字符串）；
     * keyword 按关键词命中排名、vector 按向量距离排序。
     */
    private List<SearchItemResponse> fuse(SearchMode mode, List<SearchItemResponse> result,
                                        Map<Long, Integer> vectorRank, Map<Long, Integer> keywordRank) {
        if (mode == SearchMode.HYBRID) {
            // RRF 融合排序：score = Σ 1/(K + rank)。v1 硬排序（双通道优先）是评估用例 8 暴露的缺陷——
            // 关键词通道的宽泛命中会把向量通道第 1 名挤出 top4；RRF 按排名融合，不偏袒任何通道。
            Map<Long, Double> rrf = new LinkedHashMap<>();
            vectorRank.forEach((id, rank) -> rrf.merge(id, 1.0 / (RRF_SMOOTHING_K + rank), Double::sum));
            keywordRank.forEach((id, rank) -> rrf.merge(id, 1.0 / (RRF_SMOOTHING_K + rank), Double::sum));
            result.sort((a, b) -> Double.compare(
                    rrf.getOrDefault(b.chunkId(), 0.0), rrf.getOrDefault(a.chunkId(), 0.0)));
            // record 不可变：排序后重建一份带 rrfScore 的结果
            return result.stream()
                    .map(it -> new SearchItemResponse(it.chunkId(), it.docId(), it.docTitle(), it.sectionPath(),
                            it.content(), it.vectorDistance(), it.keywordHits(), it.bothChannels(),
                            String.format("%.5f", rrf.getOrDefault(it.chunkId(), 0.0))))
                    .collect(Collectors.toList());
        }
        if (mode == SearchMode.KEYWORD) {
            result.sort((a, b) -> Integer.compare(
                    keywordRank.getOrDefault(a.chunkId(), RANK_FALLBACK),
                    keywordRank.getOrDefault(b.chunkId(), RANK_FALLBACK)));
        } else {
            result.sort((a, b) -> Double.compare(
                    a.vectorDistance() == null ? DISTANCE_FALLBACK : a.vectorDistance(),
                    b.vectorDistance() == null ? DISTANCE_FALLBACK : b.vectorDistance()));
        }
        return result;
    }
}
