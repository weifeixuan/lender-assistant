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
    public List<SearchItemResponse> hybridSearch(String question, String previousQuestion, int topK) {
        return search(question, previousQuestion, topK, SearchMode.HYBRID);
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
        return search(query, null, topK, mode);
    }

    /** 检索核心：向量通道沿用「上一轮问题 + 换行 + 当前问题」的整体文本，关键词通道按段取词 */
    private List<SearchItemResponse> search(String question, String previousQuestion, int topK, SearchMode mode) {
        String vectorQuery = previousQuestion == null ? question : previousQuestion + "\n" + question;
        List<SearchItemResponse> vectorHits =
                mode == SearchMode.KEYWORD ? List.of() : vectorChannel(vectorQuery, topK);
        List<SearchItemResponse> keywordHits =
                mode == SearchMode.VECTOR ? List.of() : keywordChannel(question, previousQuestion, topK);

        List<SearchItemResponse> merged = mergeChannels(vectorHits, keywordHits);
        List<SearchItemResponse> ranked = rank(mode, merged, vectorHits, keywordHits);
        return truncate(ranked, topK);
    }

    /** 通道 1：向量召回（按余弦距离），超出 maxDistance 阈值的命中直接丢弃 */
    private List<SearchItemResponse> vectorChannel(String query, int topK) {
        float[] queryVector = embeddingStrategy.embed(query);
        List<VectorHit> hits = docChunkMapper.searchByVector(PgVectorUtils.toLiteral(queryVector), topK);
        List<SearchItemResponse> results = new ArrayList<>();
        for (VectorHit hit : hits) {
            if (maxDistance >= 0 && hit.distance() > maxDistance) {
                continue;   // 超出阈值的召回丢弃（maxDistance<0 时该开关关闭）
            }
            SearchItemResponse item = new SearchItemResponse(hit.id(), hit.docId(), hit.docTitle(),
                    hit.sectionPath(), hit.content(), hit.distance(), null, false, null);
            results.add(item);
        }
        return results;
    }

    /**
     * 通道 2：关键词召回——分词后多词 OR 匹配（整句算相似度会被长句稀释基本不命中，拆词才有效）。
     * 过滤：单字 token 命中面太宽，只留 2 字以上；去重后限量防 SQL 过长。
     */
    private List<SearchItemResponse> keywordChannel(String question, String previousQuestion, int topK) {
        List<String> tokens = keywordTokens(question, previousQuestion);
        // 空 token 守卫下沉到 Service：MyBatis <foreach> 空集合会生成非法 SQL
        //（旧 DocumentStore 在方法内部处理 tokens.isEmpty()）
        List<KeywordHit> hits = tokens.isEmpty() ? List.of() : docChunkMapper.searchByKeyword(tokens, topK);
        List<SearchItemResponse> results = new ArrayList<>();
        for (KeywordHit hit : hits) {
            SearchItemResponse item = new SearchItemResponse(hit.id(), hit.docId(), hit.docTitle(),
                    hit.sectionPath(), hit.content(), null, hit.hits(), false, null);
            results.add(item);
        }
        return results;
    }

    /**
     * 按段分配关键词 token 配额：当前问题保底一半（总配额 8 → 当前 ≥4），其余给上一轮问题；
     * 一段不足时余额让给另一段。
     *
     * 为什么不能对拼接串直接 limit：追问的拼接顺序是「上一轮 + 换行 + 当前问题」，
     * 上一轮长问题本身就吃满 8 个 token，当前追问（如"响应字段呢？"）的词会全部被截掉，
     * 两轮的召回结果完全相同（票 05 诊断的真实现场）。
     */
    private List<String> keywordTokens(String question, String previousQuestion) {
        List<String> current = distinctKeywordTokens(question);
        // 跨段去重：当前问题的词优先保留，上一轮只补当前问题没覆盖的词
        List<String> previous = previousQuestion == null
                ? List.of()
                : distinctKeywordTokens(previousQuestion).stream()
                        .filter(token -> !current.contains(token))
                        .toList();

        int half = KEYWORD_TOKEN_MAX_COUNT / 2;
        int currentTake = Math.min(current.size(), Math.max(half, KEYWORD_TOKEN_MAX_COUNT - previous.size()));
        int previousTake = Math.min(previous.size(), KEYWORD_TOKEN_MAX_COUNT - currentTake);
        // 上一轮不足时，剩余配额回补给当前问题（当前问题优先）
        currentTake = Math.min(current.size(), KEYWORD_TOKEN_MAX_COUNT - previousTake);
        List<String> tokens = new ArrayList<>(current.subList(0, currentTake));
        tokens.addAll(previous.subList(0, previousTake));
        return tokens;
    }

    private List<String> distinctKeywordTokens(String text) {
        return HashEmbeddingStrategy.tokenize(text).stream()
                .filter(token -> token.length() >= KEYWORD_TOKEN_MIN_CHARS)
                .distinct()
                .toList();
    }

    /**
     * 合并两通道：保留向量通道顺序，关键词通道只补缺席的 chunk。
     * 同一 chunk 双通道命中时保留向量侧信息，补齐关键词命中数并置 bothChannels=true。
     */
    private List<SearchItemResponse> mergeChannels(List<SearchItemResponse> vectorHits,
                                                   List<SearchItemResponse> keywordHits) {
        Map<Long, SearchItemResponse> mergedById = new LinkedHashMap<>();
        for (SearchItemResponse hit : vectorHits) {
            mergedById.put(hit.chunkId(), hit);
        }
        for (SearchItemResponse hit : keywordHits) {
            SearchItemResponse existing = mergedById.get(hit.chunkId());
            if (existing == null) {
                mergedById.put(hit.chunkId(), hit);
                continue;
            }
            SearchItemResponse both = new SearchItemResponse(existing.chunkId(), existing.docId(),
                    existing.docTitle(), existing.sectionPath(), existing.content(),
                    existing.vectorDistance(), hit.keywordHits(), true, existing.rrfScore());
            mergedById.put(hit.chunkId(), both);
        }
        return new ArrayList<>(mergedById.values());
    }

    /** 排序：hybrid 用 RRF 按两路排名融合，keyword 按关键词排名，vector 按向量距离 */
    private List<SearchItemResponse> rank(SearchMode mode, List<SearchItemResponse> merged,
                                          List<SearchItemResponse> vectorHits,
                                          List<SearchItemResponse> keywordHits) {
        if (mode == SearchMode.HYBRID) {
            return rankByRrf(merged, vectorHits, keywordHits);
        }
        if (mode == SearchMode.KEYWORD) {
            return rankByKeyword(merged, keywordHits);
        }
        return rankByDistance(merged);
    }

    /**
     * RRF 融合排序：score = Σ 1/(K + rank)。v1 硬排序（双通道优先）是评估用例 8 暴露的缺陷——
     * 关键词通道的宽泛命中会把向量通道第 1 名挤出 top4；RRF 按排名融合，不偏袒任何通道。
     */
    private List<SearchItemResponse> rankByRrf(List<SearchItemResponse> merged,
                                               List<SearchItemResponse> vectorHits,
                                               List<SearchItemResponse> keywordHits) {
        Map<Long, Double> rrfScores = new LinkedHashMap<>();
        accumulateRrfScores(rrfScores, vectorHits);
        accumulateRrfScores(rrfScores, keywordHits);

        List<SearchItemResponse> ranked = new ArrayList<>(merged);
        ranked.sort((a, b) -> Double.compare(
                rrfScores.getOrDefault(b.chunkId(), 0.0), rrfScores.getOrDefault(a.chunkId(), 0.0)));

        // record 不可变：排序后重建一份带 rrfScore 的结果
        List<SearchItemResponse> scored = new ArrayList<>(ranked.size());
        for (SearchItemResponse hit : ranked) {
            SearchItemResponse item = new SearchItemResponse(hit.chunkId(), hit.docId(), hit.docTitle(),
                    hit.sectionPath(), hit.content(), hit.vectorDistance(), hit.keywordHits(),
                    hit.bothChannels(), String.format("%.5f", rrfScores.getOrDefault(hit.chunkId(), 0.0)));
            scored.add(item);
        }
        return scored;
    }

    /** 按通道内名次（1-based）累计 RRF 分数：同一 chunk 出现在两路时分数相加 */
    private void accumulateRrfScores(Map<Long, Double> rrfScores, List<SearchItemResponse> channelHits) {
        for (int i = 0; i < channelHits.size(); i++) {
            long chunkId = channelHits.get(i).chunkId();
            rrfScores.merge(chunkId, 1.0 / (RRF_SMOOTHING_K + i + 1), Double::sum);
        }
    }

    private List<SearchItemResponse> rankByKeyword(List<SearchItemResponse> merged,
                                                   List<SearchItemResponse> keywordHits) {
        Map<Long, Integer> keywordRanks = new LinkedHashMap<>();
        for (int i = 0; i < keywordHits.size(); i++) {
            keywordRanks.put(keywordHits.get(i).chunkId(), i + 1);
        }
        List<SearchItemResponse> ranked = new ArrayList<>(merged);
        ranked.sort((a, b) -> Integer.compare(
                keywordRanks.getOrDefault(a.chunkId(), RANK_FALLBACK),
                keywordRanks.getOrDefault(b.chunkId(), RANK_FALLBACK)));
        return ranked;
    }

    private List<SearchItemResponse> rankByDistance(List<SearchItemResponse> merged) {
        List<SearchItemResponse> ranked = new ArrayList<>(merged);
        ranked.sort((a, b) -> Double.compare(distanceOf(a), distanceOf(b)));
        return ranked;
    }

    private double distanceOf(SearchItemResponse hit) {
        return hit.vectorDistance() == null ? DISTANCE_FALLBACK : hit.vectorDistance();
    }

    /** 只取前 topK 条；结果少于 topK 时原样返回 */
    private List<SearchItemResponse> truncate(List<SearchItemResponse> ranked, int topK) {
        return ranked.subList(0, Math.min(topK, ranked.size()));
    }
}
