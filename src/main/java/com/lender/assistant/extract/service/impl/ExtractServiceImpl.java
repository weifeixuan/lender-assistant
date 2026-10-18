package com.lender.assistant.extract.service.impl;

import com.lender.assistant.extract.mapper.FieldMappingMapper;
import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.model.FieldMappingRaw;
import com.lender.assistant.extract.model.FieldMappingRow;
import com.lender.assistant.extract.model.InternalField;
import com.lender.assistant.extract.service.ExtractGeneratorService;
import com.lender.assistant.extract.service.ExtractService;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化抽取编排 + 映射表读写（原 extract/ExtractService 与 extract/FieldMappingStore 合并，§11）。
 *
 * 抽取是**非流式**一锤子调用（JSON 进 JSON 出），allowRetry=true——
 * 失败重试不影响用户侧一致性；问答 SSE 则禁止重试（token 已推给用户）。
 * 事务从 Store 迁到 saveMappings（§6.11），覆盖"清 stale + upsert"两步的原子性。
 */
@Service
public class ExtractServiceImpl implements ExtractService {

    private final ExtractGeneratorService generator;
    private final DocumentService documentService;
    private final FieldMappingMapper mapper;
    private final ResilientLlmExecutor executor;

    public ExtractServiceImpl(ExtractGeneratorService generator, DocumentService documentService,
                              FieldMappingMapper mapper, ResilientLlmExecutor executor) {
        this.generator = generator;
        this.documentService = documentService;
        this.mapper = mapper;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveMappings(String docId, List<FieldMapping> mappings) {
        // 先清掉本次没再抽出的 PENDING 旧映射（规则收紧/文档更新后消失的字段不留脏数据）；
        // 人工成果（CONFIRMED/CORRECTED）不受影响——DELETE 只限 PENDING，
        // upsert 的 WHERE status='PENDING' 再守一道。两步必须原子。
        mapper.deleteStalePending(docId);
        if (mappings.isEmpty()) {
            return 0;   // 空映射：清 stale 即完成，避免 <foreach> 生成空 VALUES
        }
        List<FieldMappingRaw> raws = new ArrayList<>(mappings.size());
        for (FieldMapping m : mappings) {
            raws.add(new FieldMappingRaw(docId, m.internalFieldId(), m.lenderFieldName(),
                    m.lenderValue(), m.sourceQuote(), m.confidence()));
        }
        return mapper.upsertAll(raws);
    }

    @Override
    public List<FieldMappingRow> listMappings(String docId) {
        return mapper.listRows(docId);
    }

    @Override
    public List<FieldMapping> listFieldMappings(String docId) {
        return mapper.listForDraft(docId).stream()
                .map(raw -> new FieldMapping(raw.internalFieldId(), internalFieldName(raw.internalFieldId()),
                        raw.lenderFieldName(), raw.lenderValue(), raw.sourceQuote(), raw.confidence()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String docId, String internalFieldId, String correctedFieldName, String correctedValue) {
        if (correctedValue != null && !correctedValue.isBlank()) {
            mapper.confirmCorrected(docId, internalFieldId, correctedFieldName, correctedValue);
        } else {
            mapper.confirmAsIs(docId, internalFieldId);
        }
    }

    /** 字段目录查不到时回退为 id 本身——与旧 FieldMappingStore.listFieldMappings 行为一致 */
    private static String internalFieldName(String internalFieldId) {
        InternalField field = InternalFieldCatalog.byId(internalFieldId);
        return field == null ? internalFieldId : field.name();
    }
}
