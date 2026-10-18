package com.lender.assistant.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.rag.service.SearchService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索质量评估（阶段 3 核心）：跑评估集 → 算指标 → 出报告。
 *
 * 指标定义（为什么选这两个，面试可讲）：
 * - recall@K：topK 条召回里"至少一条命中 ground truth"的条目占比——
 *   问答场景里检索的目标是"把相关内容送进 Prompt"，漏了就答不出，所以看 recall 而非 precision
 *   （topK 只有 4 条，进 Prompt 无所谓多几条噪声，模型自己会过滤）；
 * - MRR（Mean Reciprocal Rank）：第一条命中结果的排名倒数的均值——
 *   衡量"最好的结果排多前"，对"topK 挤不下更多内容"的 Prompt 预算敏感。
 *
 * ground truth 判定：mustContain 关键词列表，召回 chunk 内容任一命中即算相关。
 * 这是字符串级判定，对字段名/路径/格式串这类 FACT/RULE 问题足够准；
 * 语义级判定（"回答是否正确"）属于生成质量评估，等真模型 + 人工抽检，不在此脚本职责内。
 *
 * 诚实声明：当前 embedding 是 hash Mock，向量通道的数字只反映"链路通不通"，
 * 不具备语义含义。切 zhipu 后重跑同脚本，数字才有简历价值——这正是本框架的设计目的：
 * 换实现不换评估，前后对比天然成立。
 */
@Service
public class EvalService {

    private final SearchService searchService;
    private final ObjectMapper objectMapper;

    public EvalService(SearchService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    public record EvalItem(int id, String type, String question, String docId,
                           List<String> mustContain, String note) {}

    public record ItemResult(int id, String type, String question, boolean hit, int firstHitRank,
                             int recallTopK, long millis, String note) {}

    public Map<String, Object> run(int topK, String mode) throws Exception {
        List<EvalItem> items = loadEvalSet();

        List<ItemResult> results = new ArrayList<>();
        long totalMillis = 0;
        for (EvalItem item : items) {
            if ("NEGATIVE".equals(item.type())) {
                // 否定条目不参与 recall/MRR（没有"正确召回"可言），语义验收时单独看
                results.add(new ItemResult(item.id(), item.type(), item.question(), false, 0, topK, 0, item.note()));
                continue;
            }
            long t0 = System.currentTimeMillis();
            List<Map<String, Object>> hits = searchService.search(item.question(), topK, mode);
            long millis = System.currentTimeMillis() - t0;
            totalMillis += millis;

            int firstHitRank = 0;
            for (int i = 0; i < hits.size(); i++) {
                String content = String.valueOf(hits.get(i).get("content"));
                if (item.mustContain().stream().anyMatch(content::contains)) {
                    firstHitRank = i + 1;
                    break;
                }
            }
            results.add(new ItemResult(item.id(), item.type(), item.question(), firstHitRank > 0,
                    firstHitRank, topK, millis, item.note()));
        }

        // 正/负样本按 type 判别（NEGATIVE 不参与 recall/MRR）；不可用耗时判别——真实正样本可能恰好耗时 0ms 而被误剔
        List<ItemResult> positive = results.stream().filter(r -> !"NEGATIVE".equals(r.type())).toList();
        long recall = positive.stream().filter(ItemResult::hit).count();
        double recallAtK = positive.isEmpty() ? 0 : (double) recall / positive.size();
        double mrr = positive.isEmpty() ? 0
                : positive.stream().mapToDouble(r -> r.hit() ? 1.0 / r.firstHitRank() : 0).average().orElse(0);
        long avgMillis = positive.isEmpty() ? 0 : totalMillis / positive.size();

        String markdown = buildMarkdown(mode, topK, results, positive.size(), recall, recallAtK, mrr, avgMillis);

        return Map.of(
                "mode", mode,
                "topK", topK,
                "totalItems", items.size(),
                "evaluatedItems", positive.size(),
                "hits", recall,
                "recallAtK", recallAtK,
                "mrr", mrr,
                "avgMillis", avgMillis,
                "results", results,
                "markdown", markdown);
    }

    private List<EvalItem> loadEvalSet() throws Exception {
        JsonNode root = objectMapper.readTree(
                new ClassPathResource("eval/evalset.json").getInputStream());
        List<EvalItem> items = new ArrayList<>();
        for (JsonNode n : root.get("items")) {
            List<String> must = new ArrayList<>();
            n.get("mustContain").forEach(k -> must.add(k.asText()));
            items.add(new EvalItem(n.get("id").asInt(), n.get("type").asText(),
                    n.get("question").asText(),
                    n.hasNonNull("docId") ? n.get("docId").asText() : null,
                    must, n.get("note").asText()));
        }
        return items;
    }

    private String buildMarkdown(String mode, int topK, List<ItemResult> results,
                                 int evaluated, long hits, double recallAtK, double mrr, long avgMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 检索评估报告\n\n")
          .append("- 模式: **").append(mode).append("** ｜ topK: ").append(topK)
          .append(" ｜ 评估条目: ").append(evaluated).append("（NEGATIVE 条目不计入）\n")
          .append("- **recall@").append(topK).append(": ").append(String.format("%.1f%%", recallAtK * 100))
          .append("**（").append(hits).append("/").append(evaluated).append("）\n")
          .append("- **MRR: ").append(String.format("%.3f", mrr)).append("**\n")
          .append("- 平均检索耗时: ").append(avgMillis).append("ms\n\n")
          .append("| id | 问题 | 命中 | 首条命中排名 | 耗时ms | 说明 |\n|---|---|---|---|---|---|\n");
        for (ItemResult r : results) {
            sb.append("| ").append(r.id())
              .append(" | ").append(r.question())
              .append(" | ").append("NEGATIVE".equals(r.type()) ? "-" : (r.hit() ? "✅" : "❌"))
              .append(" | ").append(r.firstHitRank() > 0 ? r.firstHitRank() : "-")
              .append(" | ").append(r.millis())
              .append(" | ").append(r.note())
              .append(" |\n");
        }
        return sb.toString();
    }
}
