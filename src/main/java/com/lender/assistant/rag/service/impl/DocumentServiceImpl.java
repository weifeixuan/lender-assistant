package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.ChunkItemRow;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.rag.vo.response.ChunkItemResponse;
import com.lender.assistant.rag.vo.response.ChunkPageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * doc_chunk 数据访问编排（原 rag/store/DocumentStore 的 MyBatis 化，§11）。
 *
 * 事务从 Store 迁到这里（§6.11：Mapper 上禁止 @Transactional）。
 * 事务内只有 DELETE + 单条多行 INSERT（§7：事务内禁止长耗时操作，解析/切分/向量化留在 Controller）。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    /** docId 上限：写入 doc_chunk 的唯一标识，限制长度与字符集避免非法值穿透到 SQL 层 */
    private static final int MAX_DOC_ID_LENGTH = 64;
    /** docId 允许的字符集合：字母、数字、下划线、连字符（与前端生成规则一致） */
    private static final Pattern DOC_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    /** 切片分页缺省页码：page 从 1 开始 */
    private static final int DEFAULT_PAGE = 1;
    /** 切片分页缺省页大小 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 切片分页页大小下限/上限：越界由 Service 夹取，避免超大响应与非法 LIMIT */
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final DocChunkMapper mapper;

    public DocumentServiceImpl(DocChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows) {
        validateDocId(docId);
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

    @Override
    public ChunkPageResponse listChunkPage(String docId, Integer page, Integer size) {
        int total = mapper.countByDoc(docId);
        if (total == 0) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, "文档不存在: " + docId);
        }
        int resolvedPage = page == null || page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
        int resolvedSize = size == null
                ? DEFAULT_PAGE_SIZE
                : Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, size));
        // 用 long 计算 offset：超页码时直接返回空页，避免乘方溢出成负数传给 SQL
        long offset = (long) (resolvedPage - 1) * resolvedSize;
        List<ChunkItemRow> rows = offset >= total
                ? List.of()
                : mapper.listChunkPage(docId, resolvedSize, (int) offset);
        List<ChunkItemResponse> items = rows.stream()
                .map(r -> new ChunkItemResponse(r.chunkId(), r.index(), r.sectionPath(),
                        r.charCount(), r.content()))
                .toList();
        return new ChunkPageResponse(docId, resolvedPage, resolvedSize, total, items);
    }

    /** docId 校验：放到 Service 入口而非等 SQL 报错，非法值直接给 400 语义 */
    @Override
    public void validateDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "docId 不能为空");
        }
        if (docId.length() > MAX_DOC_ID_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "docId 长度不能超过 " + MAX_DOC_ID_LENGTH + " 个字符");
        }
        if (!DOC_ID_PATTERN.matcher(docId).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "docId 只能包含字母、数字、下划线与连字符");
        }
    }
}
