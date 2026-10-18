package com.lender.assistant.rag.service.impl;

import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * doc_chunk 数据访问编排（原 rag/store/DocumentStore 的 MyBatis 化，§11）。
 *
 * 事务从 Store 迁到这里（§6.11：Mapper 上禁止 @Transactional）。
 * 事务内只有 DELETE + 单条多行 INSERT（§7：事务内禁止长耗时操作，解析/切分/向量化留在 Controller）。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocChunkMapper mapper;

    public DocumentServiceImpl(DocChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows) {
        mapper.deleteByDoc(docId);
        if (rows.isEmpty()) {
            return;   // 空文档：删旧即完成，避免 <foreach> 生成空 VALUES（非法 SQL）
        }
        mapper.insertChunks(docId, docTitle, sourcePath, rows);
    }

    @Override
    public int countByDoc(String docId) {
        return mapper.countByDoc(docId);
    }

    @Override
    public void deleteByDoc(String docId) {
        mapper.deleteByDoc(docId);
    }

    @Override
    public List<DocSummary> listDocuments() {
        return mapper.listDocuments();
    }

    @Override
    public DocContent loadDocContent(String docId) {
        List<DocChunkLine> lines = mapper.listChunkLines(docId);
        if (lines.isEmpty()) {
            return null;
        }
        // 【章节】前缀拼接规则与旧 DocumentStore 逐字一致——抽取器靠它按章节分类接口路径。
        // 旧实现为取标题多发一条 SELECT ... LIMIT 1；同一 doc_id 的 doc_title 相同，取首行即可
        String text = lines.stream()
                .map(l -> "【章节】" + l.sectionPath() + "\n" + l.content())
                .collect(Collectors.joining("\n"));
        return new DocContent(lines.get(0).docTitle(), text);
    }
}
