package com.lender.assistant.draft.service;

import com.lender.assistant.extract.model.FieldMapping;

import java.util.List;

/**
 * 代码草稿生成端口（阶段5）：映射表 + Handler 骨架 → Java 适配层草稿文本。
 *
 * 与 EmbeddingClient/AnswerGenerator/ExtractGenerator 同构的端口-适配器：
 * Mock 实现做确定性模板渲染（可单测、无 key 可跑），DeepSeek 实现做语义生成。
 */
public interface DraftGenerator {

    /**
     * @param docId    文档标识（渲染进 lenderCode）
     * @param mappings 该文档已确认/待确认的字段映射
     * @return Java 源码草稿文本（不含 {{...}} 占位符残留）
     */
    String generateDraft(String docId, List<FieldMapping> mappings);

    String name();
}
