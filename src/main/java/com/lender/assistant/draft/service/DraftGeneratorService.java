package com.lender.assistant.draft.service;

import com.lender.assistant.extract.model.entity.FieldMappingEntity;

import java.util.List;

/**
 * 代码草稿生成模型调用服务：映射表 + Handler 骨架 → Java 适配层草稿文本。
 */
public interface DraftGeneratorService {

    /**
     * @param docId    文档标识（渲染进 lenderCode）
     * @param mappings 该文档已确认/待确认的字段映射
     * @return Java 源码草稿文本（不含 {{...}} 占位符残留）
     */
    String generateDraft(String docId, List<FieldMappingEntity> mappings);

    String name();
}
