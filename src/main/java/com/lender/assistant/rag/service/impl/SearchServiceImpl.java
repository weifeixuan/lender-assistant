package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.constant.SearchConstants;
import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.common.util.PgVectorUtils;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.dto.KeywordHitDTO;
import com.lender.assistant.rag.model.dto.VectorHitDTO;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.strategy.impl.HashEmbeddingStrategy;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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
 * - 合并策略：RRF 按两通道名次融合（score = Σ 1/(K + rank)），不偏袒任何单通道。
 *
 * 召回口径（票 09 诊断的现场）：两通道先各召回 max(2*topK, 12) 条候选，
 * 融合 + 同 section 相邻切片合并后再截断到 topK。
 * 若直接用 topK 做 LIMIT，完整承载字段清单的切片（如 ChunkDTO 4）会因排名差一两位被切掉，
 * 只剩它的尾巴（ChunkDTO 5）进入答案上下文。
 */
@Service
public class SearchServiceImpl implements SearchService {

    /** RRF 融合平滑常数：score = Σ 1/(K + rank)，60 为论文常用值（越大越不偏袒单通道的头部排名） */
    private static final int RRF_SMOOTHING_K = 60;
    /** 关键词 token 最短长度：单字 LIKE 命中面太宽（"的""是"满库都是），只留 2 字以上 */
    private static final int KEYWORD_TOKEN_MIN_CHARS = 2;
    /** 关键词 token 去重后上限：防止 <foreach> 拼出的 SQL 过长 */
    private static final int KEYWORD_TOKEN_MAX_COUNT = 8;
    /** 关键词通道排序的缺省排名：未命中关键词通道的 ChunkDTO 排到末尾 */
    private static final int RANK_FALLBACK = 99;
    /** 向量通道排序的缺省距离：仅关键词命中（无 vectorDistance）时排到末尾 */
    private static final double DISTANCE_FALLBACK = 2;
    /** 相邻切片合并时认定的最小重叠字符数：低于此长度的"巧合"首尾相同不去重，避免误删正文 */
    private static final int MIN_MERGED_OVERLAP_CHARS = 4;
    /** 分组 key 内部分隔符：docId 与 sectionPath 拼接，用 NUL 防止两者内容互相粘出歧义 key */
    private static final String SECTION_KEY_SEPARATOR = "\u0000";

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
        int candidateLimit = SearchConstants.candidateLimit(topK);
        List<ChannelHit> vectorHits =
                mode == SearchMode.KEYWORD ? List.of() : vectorChannel(vectorQuery, candidateLimit);
        List<ChannelHit> keywordHits =
                mode == SearchMode.VECTOR ? List.of() : keywordChannel(question, previousQuestion, candidateLimit);

        List<Candidate> candidates = mergeChannels(vectorHits, keywordHits);
        List<Candidate> grouped = mergeAdjacentChunks(candidates);
        List<SearchItemResponse> ranked = rank(mode, grouped);
        return truncate(ranked, topK);
    }

    /** 通道 1：向量召回（按余弦距离），超出 maxDistance 阈值的命中直接丢弃 */
    private List<ChannelHit> vectorChannel(String query, int candidateLimit) {
        float[] queryVector = embeddingStrategy.embed(query);
        List<VectorHitDTO> hits = docChunkMapper.searchByVector(PgVectorUtils.toLiteral(queryVector), candidateLimit);
        List<ChannelHit> results = new ArrayList<>();
        for (VectorHitDTO hit : hits) {
            if (maxDistance >= 0 && hit.distance() > maxDistance) {
                continue;   // 超出阈值的召回丢弃（maxDistance<0 时该开关关闭）
            }
            SearchItemResponse item = new SearchItemResponse(hit.id(), hit.docId(), hit.docTitle(),
                    hit.sectionPath(), hit.content(), hit.distance(), null, false, null);
            // 名次按"过滤后"的位置计（与旧实现 accumulateRrfScores 的口径一致）
            results.add(new ChannelHit(item, results.size() + 1, hit.chunkIndex()));
        }
        return results;
    }

    /**
     * 通道 2：关键词召回——分词后多词 OR 匹配（整句算相似度会被长句稀释基本不命中，拆词才有效）。
     * 过滤：单字 token 命中面太宽，只留 2 字以上；去重后限量防 SQL 过长。
     */
    private List<ChannelHit> keywordChannel(String question, String previousQuestion, int candidateLimit) {
        List<String> tokens = keywordTokens(question, previousQuestion);
        // 空 token 守卫下沉到 Service：MyBatis <foreach> 空集合会生成非法 SQL
        //（旧 DocumentStore 在方法内部处理 tokens.isEmpty()）
        List<KeywordHitDTO> hits = tokens.isEmpty() ? List.of() : docChunkMapper.searchByKeyword(tokens, candidateLimit);
        List<ChannelHit> results = new ArrayList<>();
        for (KeywordHitDTO hit : hits) {
            SearchItemResponse item = new SearchItemResponse(hit.id(), hit.docId(), hit.docTitle(),
                    hit.sectionPath(), hit.content(), null, hit.hits(), false, null);
            results.add(new ChannelHit(item, results.size() + 1, hit.chunkIndex()));
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
     * 合并两通道：保留向量通道顺序，关键词通道只补缺席的 ChunkDTO。
     * 同一 ChunkDTO 双通道命中时保留向量侧内容，补齐关键词命中数并置 bothChannels=true。
     */
    private List<Candidate> mergeChannels(List<ChannelHit> vectorHits, List<ChannelHit> keywordHits) {
        Map<Long, Candidate> mergedById = new LinkedHashMap<>();
        for (ChannelHit hit : vectorHits) {
            mergedById.put(hit.item().chunkId(), fromVector(hit));
        }
        for (ChannelHit hit : keywordHits) {
            Candidate existing = mergedById.get(hit.item().chunkId());
            if (existing == null) {
                mergedById.put(hit.item().chunkId(), fromKeyword(hit));
                continue;
            }
            mergedById.put(hit.item().chunkId(), new Candidate(
                    existing.chunkId(), existing.docId(), existing.docTitle(), existing.sectionPath(),
                    existing.content(), existing.chunkIndex(),
                    existing.vectorDistance(), hit.item().keywordHits(), true,
                    existing.rrfScore() + rrfOf(hit.rank()),
                    Math.min(existing.keywordRank(), hit.rank()),
                    existing.distance()));
        }
        return new ArrayList<>(mergedById.values());
    }

    private Candidate fromVector(ChannelHit hit) {
        SearchItemResponse item = hit.item();
        return new Candidate(item.chunkId(), item.docId(), item.docTitle(), item.sectionPath(), item.content(),
                hit.chunkIndex(), item.vectorDistance(), null, false,
                rrfOf(hit.rank()), RANK_FALLBACK, distanceOf(item));
    }

    private Candidate fromKeyword(ChannelHit hit) {
        SearchItemResponse item = hit.item();
        return new Candidate(item.chunkId(), item.docId(), item.docTitle(), item.sectionPath(), item.content(),
                hit.chunkIndex(), null, item.keywordHits(), false,
                rrfOf(hit.rank()), hit.rank(), DISTANCE_FALLBACK);
    }

    private double rrfOf(int rank) {
        return 1.0 / (RRF_SMOOTHING_K + rank);
    }

    /**
     * 同 section 相邻切片合并：同一 docId + sectionPath 下 chunkIndex 连续（next == prev + 1）的候选，
     * 按序号拼成一个来源。例如完整字段清单被切成 4（正文）与 5（表格尾巴）时，
     * 两片一起进入上下文，而不是只剩尾巴。
     *
     * 为什么放在召回之后而不是 SQL 聚合：是否相邻取决于本次召回的候选集合，
     * 在 SQL 层按 section 互斥聚合会改变召回语义（§0 ADR 约束禁止 SQL 层互斥过滤）。
     *
     * 合并片的语义口径：
     * - chunkId / docId / docTitle / sectionPath 取 chunkIndex 最小（首片）的那条，
     *   id 仍指向库里真实存在的切片（前端按 id 溯源不会指向不存在的记录）；
     * - content 按 chunkIndex 升序拼接，并去掉切片 overlap 的重复文本；
     * - rrfScore 取成员最大值，keywordRank / distance 取成员最小值，bothChannels 取或；
     * - vectorDistance / keywordHits 取成员中"有值且最相关"的那个（距离最小 / 命中数最多）；
     * - 对外 topK 与 sources 条数按"合并后条目"计数：一个合并片算 1 条（sourceCount 会因此变小，
     *   这是预期行为，因为多条切片本来描述的就是同一小节连续内容）。
     */
    private List<Candidate> mergeAdjacentChunks(List<Candidate> candidates) {
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        List<Candidate> ungrouped = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.chunkIndex() == null) {
                // 缺 chunkIndex 无法判断相邻关系，原样保留（不合并）
                ungrouped.add(candidate);
                continue;
            }
            String key = candidate.docId() + SECTION_KEY_SEPARATOR + candidate.sectionPath();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
        }

        List<Candidate> merged = new ArrayList<>(candidates.size());
        for (List<Candidate> group : groups.values()) {
            group.sort(Comparator.comparingInt(Candidate::chunkIndex));
            List<Candidate> run = new ArrayList<>();
            for (Candidate candidate : group) {
                if (!run.isEmpty()
                        && candidate.chunkIndex() != run.get(run.size() - 1).chunkIndex() + 1) {
                    merged.add(combine(run));
                    run.clear();
                }
                run.add(candidate);
            }
            if (!run.isEmpty()) {
                merged.add(combine(run));
            }
        }
        merged.addAll(ungrouped);
        return merged;
    }

    /** 把一组相邻切片（已按 chunkIndex 升序）拼成一个候选 */
    private Candidate combine(List<Candidate> run) {
        Candidate first = run.get(0);
        String content = first.content();
        double rrfScore = first.rrfScore();
        int keywordRank = first.keywordRank();
        double distance = first.distance();
        Double vectorDistance = first.vectorDistance();
        Integer keywordHits = first.keywordHits();
        boolean bothChannels = first.bothChannels();

        for (int i = 1; i < run.size(); i++) {
            Candidate next = run.get(i);
            content = joinWithOverlap(content, next.content());
            rrfScore = Math.max(rrfScore, next.rrfScore());
            keywordRank = Math.min(keywordRank, next.keywordRank());
            distance = Math.min(distance, next.distance());
            if (next.vectorDistance() != null
                    && (vectorDistance == null || next.vectorDistance() < vectorDistance)) {
                vectorDistance = next.vectorDistance();
            }
            if (next.keywordHits() != null
                    && (keywordHits == null || next.keywordHits() > keywordHits)) {
                keywordHits = next.keywordHits();
            }
            bothChannels = bothChannels || next.bothChannels();
        }

        return new Candidate(first.chunkId(), first.docId(), first.docTitle(), first.sectionPath(), content,
                first.chunkIndex(), vectorDistance, keywordHits, bothChannels,
                rrfScore, keywordRank, distance);
    }

    /**
     * 拼接两片：切片器按 chunkSize - overlap 步长切分，相邻切片首尾有 overlap 个字符重复。
     * 取"前一片的后缀 == 后一片的前缀"的最长重叠并跳过，避免同一段文字出现两次；
     * 未找到足够长重叠时直接首尾相接（两片本来就是原文的连续子串）。
     */
    private String joinWithOverlap(String head, String tail) {
        String next = tail.stripLeading();
        int max = Math.min(head.length(), next.length());
        for (int overlap = max; overlap >= MIN_MERGED_OVERLAP_CHARS; overlap--) {
            if (head.endsWith(next.substring(0, overlap))) {
                return head + next.substring(overlap);
            }
        }
        return head + tail;
    }

    /** 排序：hybrid 用 RRF 分数降序，keyword 按关键词名次，vector 按向量距离 */
    private List<SearchItemResponse> rank(SearchMode mode, List<Candidate> candidates) {
        if (mode == SearchMode.HYBRID) {
            candidates.sort(Comparator.comparingDouble(Candidate::rrfScore).reversed());
        } else if (mode == SearchMode.KEYWORD) {
            candidates.sort(Comparator.comparingInt(Candidate::keywordRank));
        } else {
            candidates.sort(Comparator.comparingDouble(Candidate::distance));
        }
        List<SearchItemResponse> results = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            results.add(toResponse(candidate, mode));
        }
        return results;
    }

    /** 对外契约不变：rrfScore 仅在 hybrid 模式输出，其它模式缺该字段 */
    private SearchItemResponse toResponse(Candidate candidate, SearchMode mode) {
        String rrfScore = mode == SearchMode.HYBRID
                ? String.format("%.5f", candidate.rrfScore())
                : null;
        return new SearchItemResponse(candidate.chunkId(), candidate.docId(), candidate.docTitle(),
                candidate.sectionPath(), candidate.content(), candidate.vectorDistance(),
                candidate.keywordHits(), candidate.bothChannels(), rrfScore);
    }

    private double distanceOf(SearchItemResponse hit) {
        return hit.vectorDistance() == null ? DISTANCE_FALLBACK : hit.vectorDistance();
    }

    /** 只取前 topK 条；结果少于 topK 时原样返回 */
    private List<SearchItemResponse> truncate(List<SearchItemResponse> ranked, int topK) {
        return ranked.subList(0, Math.min(topK, ranked.size()));
    }

    /** 单通道命中：rank 为通道内 1-based 名次（RRF 用），chunkIndex 供相邻切片合并判断 */
    private record ChannelHit(SearchItemResponse item, int rank, Integer chunkIndex) {
    }

    /**
     * 融合与合并阶段使用的候选：把两通道的分数/名次/距离收敛到一条记录，
     * 便于排序（rrfScore/keywordRank/distance）与相邻切片合并（chunkIndex）。
     */
    private record Candidate(long chunkId, String docId, String docTitle, String sectionPath, String content,
                             Integer chunkIndex, Double vectorDistance, Integer keywordHits,
                             boolean bothChannels, double rrfScore, int keywordRank, double distance) {
    }
}
