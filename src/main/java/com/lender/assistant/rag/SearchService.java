package com.lender.assistant.rag;

import com.lender.assistant.rag.embedder.EmbeddingClient;
import com.lender.assistant.rag.embedder.HashEmbeddingClient;
import com.lender.assistant.rag.store.DocumentStore;
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
public class SearchService {

    private final EmbeddingClient embeddingClient;
    private final DocumentStore store;

    /**
     * 向量通道相似度阈值：余弦距离大于该值的召回被丢弃，用于挡住"完全不相关"的命中。
     * 默认 -1 = 关闭（不改变现有召回行为）——是否开启由阶段 3 评估数据决定，
     * 故此处只提供开关而非直接改默认语义。
     */
    private final double maxDistance;

    public SearchService(EmbeddingClient embeddingClient, DocumentStore store,
                         @Value("${rag.search.max-distance:-1}") double maxDistance) {
        this.embeddingClient = embeddingClient;
        this.store = store;
        this.maxDistance = maxDistance;
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        return search(query, topK, "hybrid");
    }

    /**
     * mode 参数是阶段 3 评估的实验开关：
     * - vector  只走向量通道：量化"语义召回"的单独贡献
     * - keyword 只走关键词通道：量化"字面召回"的单独贡献
     * - hybrid  双通道合并（生产路径）
     * 三模式跑同一评估集的对比，就是简历上"混合检索比单通道召回率提升 XX%"数据的来源。
     */
    public List<Map<String, Object>> search(String query, int topK, String mode) {
        // 每条 chunk 记录它在各通道内的排名（1-based），hybrid 模式用 RRF 融合
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        Map<Long, Integer> vectorRank = new LinkedHashMap<>();
        Map<Long, Integer> keywordRank = new LinkedHashMap<>();

        // 通道 1：向量召回
        if (!"keyword".equals(mode)) {
            float[] queryVec = embeddingClient.embed(query);
            List<DocumentStore.VectorHit> hits = store.searchByVector(queryVec, topK);
            int rank = 0;
            for (DocumentStore.VectorHit hit : hits) {
                if (maxDistance >= 0 && hit.distance() > maxDistance) {
                    continue;   // 超出阈值的召回丢弃（maxDistance<0 时该开关关闭）
                }
                rank++;
                Map<String, Object> m = base(hit.id(), hit.docId(), hit.docTitle(), hit.sectionPath(), hit.content());
                m.put("vectorDistance", hit.distance());
                m.put("bothChannels", false);
                merged.put(hit.id(), m);
                vectorRank.put(hit.id(), rank);
            }
        }

        // 通道 2：关键词召回——分词后多词 OR 匹配（v1 整句 trigram 的教训见 DocumentStore）
        // 过滤：单字 token LIKE 命中面太宽（"的""是"满库都是），只留 2 字以上；去重后限 8 个防 SQL 过长
        if (!"vector".equals(mode)) {
            List<String> tokens = HashEmbeddingClient.tokenize(query).stream()
                    .filter(t -> t.length() >= 2)
                    .distinct()
                    .limit(8)
                    .toList();
            List<DocumentStore.KeywordHit> hits = store.searchByKeyword(tokens, topK);
            for (int i = 0; i < hits.size(); i++) {
                DocumentStore.KeywordHit hit = hits.get(i);
                keywordRank.put(hit.id(), i + 1);
                Map<String, Object> m = merged.get(hit.id());
                if (m != null) {
                    m.put("bothChannels", true);
                    m.put("keywordHits", hit.hits());
                } else {
                    m = base(hit.id(), hit.docId(), hit.docTitle(), hit.sectionPath(), hit.content());
                    m.put("keywordHits", hit.hits());
                    m.put("bothChannels", false);
                    merged.put(hit.id(), m);
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(merged.values());

        if ("hybrid".equals(mode)) {
            // RRF 融合排序：score = Σ 1/(K + rank)，K=60 为论文常用平滑常数。
            // v1 硬排序（双通道优先）是评估用例 8 暴露的缺陷——关键词通道的宽泛命中
            // 会把向量通道第 1 名挤出 top4；RRF 按排名融合，不偏袒任何通道。
            final int K = 60;
            Map<Long, Double> rrf = new LinkedHashMap<>();
            vectorRank.forEach((id, rank) -> rrf.merge(id, 1.0 / (K + rank), Double::sum));
            keywordRank.forEach((id, rank) -> rrf.merge(id, 1.0 / (K + rank), Double::sum));
            result.sort((a, b) -> Double.compare(
                    rrf.getOrDefault(chunkId(b), 0.0), rrf.getOrDefault(chunkId(a), 0.0)));
            result.forEach(m -> m.put("rrfScore",
                    String.format("%.5f", rrf.getOrDefault(chunkId(m), 0.0))));
        } else if ("keyword".equals(mode)) {
            result.sort((a, b) -> Integer.compare(
                    keywordRank.getOrDefault(chunkId(a), 99), keywordRank.getOrDefault(chunkId(b), 99)));
        } else {
            result.sort((a, b) -> Double.compare(
                    a.get("vectorDistance") == null ? 2 : (double) a.get("vectorDistance"),
                    b.get("vectorDistance") == null ? 2 : (double) b.get("vectorDistance")));
        }
        return result.subList(0, Math.min(topK, result.size()));
    }

    private long chunkId(Map<String, Object> m) {
        return ((Number) m.get("chunkId")).longValue();
    }

    private Map<String, Object> base(long id, String docId, String docTitle, String sectionPath, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkId", id);
        m.put("docId", docId);
        m.put("docTitle", docTitle);
        m.put("sectionPath", sectionPath);
        m.put("content", content);
        return m;
    }
}
