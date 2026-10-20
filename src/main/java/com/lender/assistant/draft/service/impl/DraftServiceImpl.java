package com.lender.assistant.draft.service.impl;

import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.draft.service.DraftService;
import com.lender.assistant.draft.strategy.DraftGeneratorStrategy;
import com.lender.assistant.draft.vo.response.DraftResponse;
import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.service.ExtractService;
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

    private final DraftGeneratorStrategy generator;
    private final ExtractService extractService;
    private final ResilientLlmExecutor executor;

    public DraftServiceImpl(DraftGeneratorStrategy generator, ExtractService extractService, ResilientLlmExecutor executor) {
        this.generator = generator;
        this.extractService = extractService;
        this.executor = executor;
    }

    @Override
    public DraftResponse generate(String docId) {
        List<FieldMapping> mappings = extractService.listFieldMappings(docId);
        // 业务失败抛 BizException 而非裸 IllegalArgumentException（§5.3）：带 ErrorCode 供全局处理器定 HTTP 状态与对外 code
        if (mappings.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该文档暂无字段映射，请先执行字段抽取：POST /api/extract/" + docId);
        }
        String draft = executor.execute(LlmScene.DRAFT, generator.name(),
                () -> generator.generateDraft(docId, mappings), true);
        return new DraftResponse(docId, generator.name(), REVIEW_NOTICE, mappings.size(), draft);
    }
}
