package com.lender.assistant.rag.controller;

import com.lender.assistant.common.constant.SearchConstants;
import com.lender.assistant.common.util.PgVectorUtils;
import com.lender.assistant.common.util.TopKUtils;
import com.lender.assistant.rag.model.Chunk;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.strategy.ChunkSplitterStrategy;
import com.lender.assistant.rag.strategy.DocumentParserStrategy;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.strategy.factory.DocumentParserStrategyFactory;
import com.lender.assistant.rag.vo.request.SearchRequest;
import com.lender.assistant.rag.vo.response.ChunkPageResponse;
import com.lender.assistant.rag.vo.response.DeleteResponse;
import com.lender.assistant.rag.vo.response.IngestMillisResponse;
import com.lender.assistant.rag.vo.response.SearchItemResponse;
import com.lender.assistant.rag.vo.response.UploadResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库与检索的管理接口（阶段 1 的操作台）。
 *
 * 入库是"流水线"编排：解析 -> 切分 -> 向量化 -> 原子替换入库，
 * 每一步的产出统计都返回给调用方——阶段 3 调优对比实验就靠这些统计数字。
 */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentAdminController {

    private final DocumentParserStrategyFactory parserFactory;
    private final ChunkSplitterStrategy splitter;
    private final EmbeddingStrategy embeddingStrategy;
    private final DocumentService documentService;
    private final SearchService searchService;

    public DocumentAdminController(DocumentParserStrategyFactory parserFactory, ChunkSplitterStrategy splitter,
                                   EmbeddingStrategy embeddingStrategy, DocumentService documentService,
                                   SearchService searchService) {
        this.parserFactory = parserFactory;
        this.splitter = splitter;
        this.embeddingStrategy = embeddingStrategy;
        this.documentService = documentService;
        this.searchService = searchService;
    }

    /**
     * 上传并入库。docId 由调用方指定（如 huaxin_corp_api_v1），
     * 重复上传同一 docId = 覆盖重建，方便反复实验。
     */
    @PostMapping
    public UploadResponse upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("docId") String docId) throws Exception {
        // 先校验再解析：非法 docId 不该白跑一次文件解析与一批外部向量化调用
        documentService.validateDocId(docId);
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        // 解析器按扩展名运行时分发，交给工厂选择（不支持的类型抛可读异常）
        DocumentParserStrategy parser = parserFactory.resolve(fileName);

        long t0 = System.currentTimeMillis();
        var parsed = parser.parse(fileName, file.getBytes());
        long t1 = System.currentTimeMillis();
        List<Chunk> chunks = splitter.split(parsed);
        long t2 = System.currentTimeMillis();

        List<ChunkRow> rows = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            // 拼接 sectionPath 进向量化输入：标题是强语义信号，丢掉太可惜
            String embedInput = c.sectionPath() == null || c.sectionPath().isEmpty()
                    ? c.content()
                    : c.sectionPath() + "\n" + c.content();
            rows.add(new ChunkRow(c.sectionPath(), c.index(), c.content(),
                    c.charCount(), PgVectorUtils.toLiteral(embeddingStrategy.embed(embedInput))));
        }
        long t3 = System.currentTimeMillis();

        documentService.replaceDocument(docId, stripExtension(fileName), fileName, rows);
        long t4 = System.currentTimeMillis();

        return new UploadResponse(docId, parsed.blocks().size(), rows.size(),
                rows.stream().mapToInt(ChunkRow::charCount).average().orElse(0),
                new IngestMillisResponse(t1 - t0, t2 - t1, t3 - t2, t4 - t3),
                embeddingStrategy.getClass().getSimpleName());
    }

    @GetMapping
    public List<DocSummary> list() {
        return documentService.listDocuments();
    }

    /**
     * 切片分页（前端"查看切分信息"抽屉的数据源）。
     * page 从 1 开始、size 1-100，未传/越界由 Service 归一；走全局 ApiResponse 包装。
     */
    @GetMapping("/{docId}/chunks")
    public ChunkPageResponse chunks(@PathVariable String docId,
                                    @RequestParam(name = "page", required = false) Integer page,
                                    @RequestParam(name = "size", required = false) Integer size) {
        return documentService.listChunkPage(docId, page, size);
    }

    @DeleteMapping("/{docId}")
    public DeleteResponse delete(@PathVariable String docId) {
        int before = documentService.countByDoc(docId);
        documentService.deleteByDoc(docId);
        return new DeleteResponse(docId, before);
    }

    /** 检索测试入口（阶段 2 会挪到面向问答的接口上） */
    @PostMapping("/search")
    public List<SearchItemResponse> search(@RequestBody SearchRequest request) {
        return searchService.hybridSearch(request.query(),
                TopKUtils.resolve(request.topK(), SearchConstants.ADMIN_SEARCH_DEFAULT_TOP_K));
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
