package com.lender.assistant.rag.strategy.impl;

import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 智谱 embedding-3 真实实现。
 *
 * 设计要点：
 * 1. 不引智谱官方 starter——它启动时强校验 key（同 DeepSeek starter 的坑），
 *    用 RestClient 手写调用反而只有几十行，且依赖零增加；
 * 2. fail fast：provider 配成 zhipu 但 key 缺失时启动即报错并给出明确提示，
 *    避免跑到入库一半才发现；
 * 3. dimensions=1024 与建库 schema 对齐（doc_chunk.embedding 是 vector(1024)），
 *    维度不一致 PG 会直接拒绝写入——这是好事，错误在边界处暴露。
 */
@Component
@ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "zhipu")
public class ZhipuEmbeddingStrategy implements EmbeddingStrategy {

    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/embeddings";

    /** 与 application.yml 中 rag.embedding.api-key 的假值占位保持一致；命中即视为未配置 */
    private static final String PLACEHOLDER_KEY = "fake-zhipu-api-key";

    private final RestClient restClient;
    private final int dimension;

    public ZhipuEmbeddingStrategy(@Value("${rag.embedding.api-key:}") String apiKey,
                                  @Value("${rag.embedding.dimension}") int dimension) {
        if (apiKey == null || apiKey.isBlank() || PLACEHOLDER_KEY.equals(apiKey)) {
            throw new IllegalStateException(
                    "rag.embedding.provider=zhipu 但未配置真实 ZHIPU_API_KEY（当前为假值占位）。"
                    + "请在 .env / 系统环境变量中配置真实 key，或切回 provider=hash 使用本地 Mock。");
        }
        this.dimension = dimension;
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        Map<String, Object> resp = restClient.post()
                .body(Map.of(
                        "model", "embedding-3",
                        "input", text,
                        "dimensions", dimension))
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = resp == null ? List.of() : (List<Map<String, Object>>) resp.get("data");
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("智谱 embedding 响应为空: " + resp);
        }
        List<Number> embedding = (List<Number>) data.get(0).get("embedding");
        if (embedding == null || embedding.size() != dimension) {
            throw new IllegalStateException("智谱返回向量维度 " + (embedding == null ? 0 : embedding.size())
                    + " 与期望 " + dimension + " 不一致");
        }
        float[] vec = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vec[i] = embedding.get(i).floatValue();
        }
        return vec;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
