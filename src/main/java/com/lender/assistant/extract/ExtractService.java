package com.lender.assistant.extract;

import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.rag.store.DocumentStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结构化抽取编排：按 docId 加载已入库文档 → 拼全文 → 经留痕调抽取器。
 *
 * 和 QaService 的关系：两者都走"加载 → 拼 Prompt → 模型端口 → 留痕"的形态，
 * 但抽取是**非流式**的一锤子调用（JSON 进 JSON 出），
 * 韧性包装（超时/重试/熔断）在阶段4后半段由 ResilientLlmExecutor 统一接入。
 */
@Service
public class ExtractService {

    private final ExtractGenerator generator;
    private final DocumentStore store;
    private final LlmCallLogger logger;

    public ExtractService(ExtractGenerator generator, DocumentStore store, LlmCallLogger logger) {
        this.generator = generator;
        this.store = store;
        this.logger = logger;
    }

    public List<FieldMapping> extractFromDoc(String docId) {
        DocumentStore.DocContent doc = store.loadDocContent(docId);
        if (doc == null || doc.text().isBlank()) {
            throw new IllegalArgumentException("文档不存在或未入库: " + docId);
        }
        return logger.record("extract", generator.name(),
                () -> generator.extract(doc.docTitle(), doc.text()));
    }
}
