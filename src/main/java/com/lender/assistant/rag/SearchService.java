package com.lender.assistant.rag;

import com.lender.assistant.rag.embedder.EmbeddingClient;
import com.lender.assistant.rag.embedder.HashEmbeddingClient;
import com.lender.assistant.rag.store.DocumentStore;
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

    public SearchService(EmbeddingClient embeddingClient, DocumentStore store) {
        this.embeddingClient = embeddingClient;
        this.store = store;
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();

        // 通道 1：向量召回
        float[] queryVec = embeddingClient.embed(query);
        for (DocumentStore.VectorHit hit : store.searchByVector(queryVec, topK)) {
            Map<String, Object> m = base(hit.docId(), hit.docTitle(), hit.sectionPath(), hit.content());
            m.put("vectorDistance", hit.distance());
            m.put("bothChannels", false);
            merged.put(hit.id(), m);
        }

        // 通道 2：关键词召回——分词后多词 OR 匹配（v1 整句 trigram 的教训见 DocumentStore）
        // 过滤：单字 token LIKE 命中面太宽（"的""是"满库都是），只留 2 字以上；去重后限 8 个防 SQL 过长
        List<String> tokens = HashEmbeddingClient.tokenize(query).stream()
                .filter(t -> t.length() >= 2)
                .distinct()
                .limit(8)
                .toList();
        for (DocumentStore.KeywordHit hit : store.searchByKeyword(tokens, topK)) {
            Map<String, Object> existing = merged.get(hit.id());
            if (existing != null) {
                existing.put("bothChannels", true);
                existing.put("keywordHits", hit.hits());
            } else {
                Map<String, Object> m = base(hit.docId(), hit.docTitle(), hit.sectionPath(), hit.content());
                m.put("keywordHits", hit.hits());
                m.put("bothChannels", false);
                merged.put(hit.id(), m);
            }
        }

        // 排序：双通道命中 > 向量距离小 > 关键词命中词数多
        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> {
            boolean ab = (boolean) a.get("bothChannels");
            boolean bb = (boolean) b.get("bothChannels");
            if (ab != bb) {
                return ab ? -1 : 1;
            }
            int ka = a.get("keywordHits") == null ? 0 : (int) a.get("keywordHits");
            int kb = b.get("keywordHits") == null ? 0 : (int) b.get("keywordHits");
            if (ka != kb) {
                return Integer.compare(kb, ka);
            }
            double da = a.get("vectorDistance") == null ? 2 : (double) a.get("vectorDistance");
            double db = b.get("vectorDistance") == null ? 2 : (double) b.get("vectorDistance");
            return Double.compare(da, db);
        });
        return result.subList(0, Math.min(topK, result.size()));
    }

    private Map<String, Object> base(String docId, String docTitle, String sectionPath, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", docId);
        m.put("docTitle", docTitle);
        m.put("sectionPath", sectionPath);
        m.put("content", content);
        return m;
    }
}
