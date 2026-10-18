package com.lender.assistant.draft.service.impl;

import com.lender.assistant.draft.service.DraftGenerator;
import com.lender.assistant.draft.service.DraftService;
import com.lender.assistant.extract.FieldMappingStore;
import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代码草稿编排（阶段5）：加载映射表 → 经韧性执行器调生成器 → 附复核声明。
 *
 * 与 ExtractService 的关键差异：
 * - 输入不是文档全文而是**已抽取的字段映射表**——没有映射就没有草稿的原料，
 *   空映射直接引导先抽取（宁缺勿编：不让模型在没有依据时凭空编字段）；
 * - 非流式一锤子调用，allowRetry=true（和抽取同策略，SSE 才禁重试）。
 */
@Service
public class DraftServiceImpl implements DraftService {

    /** 人工复核声明（手册："展示 + 人工复核提示"）——前端醒目展示，永不省略 */
    static final String REVIEW_NOTICE =
            "代码草稿由 AI 依据字段映射表生成，必须逐行人工复核、补全报文组装与签名逻辑后方可使用，严禁直接上生产。";

    private final DraftGenerator generator;
    private final FieldMappingStore store;
    private final ResilientLlmExecutor executor;

    public DraftServiceImpl(DraftGenerator generator, FieldMappingStore store, ResilientLlmExecutor executor) {
        this.generator = generator;
        this.store = store;
        this.executor = executor;
    }

    @Override
    public DraftService.DraftResult generate(String docId) {
        List<FieldMapping> mappings = store.listFieldMappings(docId);
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException(
                    "该文档暂无字段映射，请先执行字段抽取：POST /api/extract/" + docId);
        }
        String draft = executor.execute("draft", generator.name(),
                () -> generator.generateDraft(docId, mappings), true);
        return new DraftService.DraftResult(docId, generator.name(), REVIEW_NOTICE, mappings.size(), draft);
    }
}
