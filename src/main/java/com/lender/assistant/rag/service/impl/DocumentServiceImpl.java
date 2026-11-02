package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.util.PgVectorUtils;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.Chunk;
import com.lender.assistant.rag.model.ChunkItemRow;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.model.ParsedDocument;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.rag.strategy.ChunkSplitterStrategy;
import com.lender.assistant.rag.strategy.DocumentParserStrategy;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.strategy.factory.DocumentParserStrategyFactory;
import com.lender.assistant.rag.vo.response.ChunkItemResponse;
import com.lender.assistant.rag.vo.response.ChunkPageResponse;
import com.lender.assistant.rag.vo.response.IngestMillisResponse;
import com.lender.assistant.rag.vo.response.UploadResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * doc_chunk 数据访问编排（原 rag/store/DocumentStore 的 MyBatis 化，§11）。
 *
 * 事务从 Store 迁到这里（§6.11：Mapper 上禁止 @Transactional）。
 * 事务内只有 DELETE + 单条多行 INSERT；解析/切分/向量化在 {@link #ingestDocument} 里、事务之外完成（§7）。
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
    private final DocumentParserStrategyFactory parserFactory;
    private final ChunkSplitterStrategy splitter;
    private final EmbeddingStrategy embeddingStrategy;
    /** 自身代理：类内直接调 replaceDocument 不经过 Spring 代理，@Transactional 会失效；经它调用才能保证删+插原子 */
    private final DocumentService self;

    public DocumentServiceImpl(DocChunkMapper mapper, DocumentParserStrategyFactory parserFactory,
                               ChunkSplitterStrategy splitter, EmbeddingStrategy embeddingStrategy,
                               @Lazy DocumentService self) {
        this.mapper = mapper;
        this.parserFactory = parserFactory;
        this.splitter = splitter;
        this.embeddingStrategy = embeddingStrategy;
        this.self = self;
    }

    @Override
    public UploadResponse ingestDocument(String docId, String fileName, byte[] fileBytes) throws Exception {
        // 解析器按扩展名运行时分发，交给工厂选择（不支持的类型抛可读异常）
        DocumentParserStrategy parser = parserFactory.resolve(fileName);

        long parseStart = System.currentTimeMillis();
        ParsedDocument parsed = parser.parse(fileName, fileBytes);
        long parseMillis = System.currentTimeMillis() - parseStart;

        long splitStart = System.currentTimeMillis();
        List<Chunk> chunks = splitter.split(parsed);
        long splitMillis = System.currentTimeMillis() - splitStart;

        long embedStart = System.currentTimeMillis();
        List<ChunkRow> rows = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            // 拼接 sectionPath 进向量化输入：标题是强语义信号，丢掉太可惜
            String embedInput = chunk.sectionPath() == null || chunk.sectionPath().isEmpty()
                    ? chunk.content()
                    : chunk.sectionPath() + "\n" + chunk.content();
            ChunkRow row = new ChunkRow(chunk.sectionPath(), chunk.index(), chunk.content(),
                    chunk.charCount(), PgVectorUtils.toLiteral(embeddingStrategy.embed(embedInput)));
            rows.add(row);
        }
        long embedMillis = System.currentTimeMillis() - embedStart;

        long storeStart = System.currentTimeMillis();
        self.replaceDocument(docId, stripExtension(fileName), fileName, rows);
        long storeMillis = System.currentTimeMillis() - storeStart;

        double avgChunkChars = rows.stream().mapToInt(ChunkRow::charCount).average().orElse(0);
        return new UploadResponse(docId, parsed.blocks().size(), rows.size(), avgChunkChars,
                new IngestMillisResponse(parseMillis, splitMillis, embedMillis, storeMillis),
                embeddingStrategy.getClass().getSimpleName());
    }

    /** 标题去扩展名：frontend 展示用（"xxx.docx" → "xxx"） */
    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
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
        return new DocContent(lines.get(0).docTitle(), text, lines);
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
