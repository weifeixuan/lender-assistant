package com.lender.assistant.rag.embedder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地 Mock 向量化：确定性哈希向量，零外部依赖。
 *
 * 用途：没有 API key 时把"解析→切分→向量化→入库→检索"整条链路跑通，
 * 并保证功能开发不被网络/费用阻塞。真实 API 就绪后切 rag.embedding.provider=zhipu。
 *
 * 原理（面试可讲）：
 * 1. 分词：中文按"相邻两字 bigram"切（"逾期天数"->逾期/期天/天数），
 *    英文/数字按词切，统一小写——bigram 比单字区分度高、比分词器简单；
 * 2. 每个 token 用 String.hashCode() 取模投进 [0, dimension) 的桶，
 *    桶内累加词频（String.hashCode 由 JLS 规范定义，跨进程跨重启结果一致）；
 * 3. 对桶值做 L2 归一化，保证任意两向量余弦相似度落在 [-1,1]。
 *
 * 局限（必须知道）：哈希碰撞导致语义完全无关的文本可能有非零相似度；
 * 且它不懂语义——"逾期"和"过期末还"毫无关系。所以它只能验证"链路对不对"，
 * 不能用来评估"检索效果好不好"（那是阶段 3 换真向量后的事）。
 */
@Component
@ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "hash")
public class HashEmbeddingClient implements EmbeddingClient {

    private final int dimension;

    public HashEmbeddingClient(@org.springframework.beans.factory.annotation.Value("${rag.embedding.dimension}") int dimension) {
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vec = new float[dimension];
        for (String token : tokenize(text)) {
            int bucket = Math.floorMod(token.hashCode(), dimension);
            vec[bucket] += 1f;
        }
        // L2 归一化：除以向量模长，让余弦相似度计算结果稳定
        double norm = 0;
        for (float v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /**
     * 生成 token 流：中文 bigram + ASCII 词。
     * 双用途：① 本类向量化用；② 检索关键词通道分词用（SearchService），
     * 故设为 public——同一个分词口径保证"入库时怎么切、查询时怎么切"一致。
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String s = text.toLowerCase();
        int n = s.length();
        boolean prevAsciiWord = false;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ascii) {
                word.append(c);
                prevAsciiWord = true;
            } else {
                if (prevAsciiWord) {
                    tokens.add(word.toString());
                    word.setLength(0);
                    prevAsciiWord = false;
                }
                if (c > 127) {
                    // 中文字符：当前字 + 下一字组成 bigram
                    tokens.add(String.valueOf(c));
                    if (i + 1 < n && s.charAt(i + 1) > 127) {
                        tokens.add("" + c + s.charAt(i + 1));
                    }
                }
                // ASCII 标点/空白：跳过
            }
        }
        if (prevAsciiWord) {
            tokens.add(word.toString());
        }
        return tokens;
    }
}
