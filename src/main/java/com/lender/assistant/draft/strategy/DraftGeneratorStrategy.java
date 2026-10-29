package com.lender.assistant.draft.strategy;

import com.lender.assistant.extract.model.FieldMapping;

import java.util.List;

/**
 * 代码草稿生成策略（阶段5）：映射表 + Handler 骨架 → Java 适配层草稿文本。
 *
 * 与 EmbeddingStrategy/AnswerGeneratorStrategy/ExtractGeneratorStrategy 同构的端口-适配器：
 * Mock 实现做确定性模板渲染（可单测、无 key 可跑），OpenAI 兼容实现做语义生成。
 * provider 二选一（mock / openai）由 @ConditionalOnProperty 在构建期决定，不需要工厂。
 */
public interface DraftGeneratorStrategy {

    /**
     * @param docId    文档标识（渲染进 lenderCode）
     * @param mappings 该文档已确认/待确认的字段映射
     * @return Java 源码草稿文本（不含 {{...}} 占位符残留）
     */
    String generateDraft(String docId, List<FieldMapping> mappings);

    String name();
}
