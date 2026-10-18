package com.lender.assistant.extract.service.impl;

import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.service.ExtractGenerator;
import com.lender.assistant.extract.service.ExtractService;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结构化抽取编排：按 docId 加载已入库文档 → 拼全文 → 经韧性执行器调抽取器。
 *
 * 和 QaService 的关系：两者都走"加载 → 模型端口 → 韧性 → 留痕"的形态。
 * 抽取是**非流式**一锤子调用（JSON 进 JSON 出），allowRetry=true——
 * 失败重试不影响用户侧一致性；问答 SSE 则禁止重试（token 已推给用户）。
 */
@Service
public class ExtractServiceImpl implements ExtractService {

    private final ExtractGenerator generator;
    private final DocumentService documentService;
    private final ResilientLlmExecutor executor;

    public ExtractServiceImpl(ExtractGenerator generator, DocumentService documentService, ResilientLlmExecutor executor) {
        this.generator = generator;
        this.documentService = documentService;
        this.executor = executor;
    }

    @Override
    public List<FieldMapping> extractFromDoc(String docId) {
        DocContent doc = documentService.loadDocContent(docId);
        if (doc == null || doc.text().isBlank()) {
            throw new IllegalArgumentException("文档不存在或未入库: " + docId);
        }
        return executor.execute("extract", generator.name(),
                () -> generator.extract(doc.docTitle(), doc.text()), true);
    }
}
