package com.lender.assistant.rag.strategy;

/**
 * 文本向量化策略抽象。
 *
 * 为什么自己定义接口而不用 Spring AI 的 EmbeddingModel：
 * 1. 智谱的 starter 在 key 缺失时启动报错（和 DeepSeek starter 一样的强校验），
 *    而"没有 key 也要能开发"是当前阶段硬需求；
 * 2. 换模型供应商时不希望业务代码感知——解析/切分/入库只依赖本接口。
 *
 * 实现类由 rag.embedding.provider 配置决定装配哪个：
 * hash  -> HashEmbeddingStrategy（本地 Mock，开发联调）
 * zhipu -> ZhipuEmbeddingStrategy（真实 API）
 */
public interface EmbeddingStrategy {

    /** 把一段文本向量化，返回固定维度的向量 */
    float[] embed(String text);

    /** 向量维度（建库和校验用） */
    int dimension();
}
