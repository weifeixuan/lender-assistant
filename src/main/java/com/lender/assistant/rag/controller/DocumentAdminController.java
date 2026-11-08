package com.lender.assistant.rag.controller;

import com.lender.assistant.common.constant.SearchConstants;
import com.lender.assistant.common.util.TopKUtils;
import com.lender.assistant.rag.model.dto.DocSummaryDTO;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.model.request.SearchRequest;
import com.lender.assistant.rag.model.response.ChunkPageResponse;
import com.lender.assistant.rag.model.response.DeleteResponse;
import com.lender.assistant.rag.model.response.DocSummaryResponse;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import com.lender.assistant.rag.model.response.UploadResponse;
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

import java.io.IOException;
import java.util.List;

/**
 * 文档入库与检索的管理接口（阶段 1 文档链路）。
 *
 * 入库是"流水线"（解析 -> 切分 -> 向量化 -> 原子替换入库），编排在 DocumentService；
 * 本类只做参数校验与响应组装。
 */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentAdminController {

    private final DocumentService documentService;
    private final SearchService searchService;

    public DocumentAdminController(DocumentService documentService, SearchService searchService) {
        this.documentService = documentService;
        this.searchService = searchService;
    }

    /**
     * 上传并入库。docId 由调用方指定（如 huaxin_corp_api_v1）；同一 docId 内容有变化 = 覆盖重建。
     *
     * 内容判重：以 docId=X 上传内容 C、而 C 已在库中时，返回的是**既有内容的 docId**（可能不等于 X）；
     * 该 docId 既不会被本调用创建、也不会被覆盖。
     */
    @PostMapping
    public UploadResponse upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("docId") String docId) throws IOException {
        // 先校验再解析：非法 docId 不该白跑一次文件解析与一批外部向量化调用
        documentService.validateDocId(docId);
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        return documentService.ingestDocument(docId, fileName, file.getBytes());
    }

    @GetMapping
    public List<DocSummaryResponse> list() {
        return documentService.listDocuments().stream()
                .map(DocumentAdminController::toResponse)
                .toList();
    }

    private static DocSummaryResponse toResponse(DocSummaryDTO doc) {
        return new DocSummaryResponse(doc.docId(), doc.docTitle(), doc.sourcePath(),
                doc.chunkCount(), doc.charCountTotal(), doc.updateTime());
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

    /**
     * 检索测试入口（阶段 2 会挪到面向问答的接口上）。
     * previousQuestion 传 null 表示"没有上一轮问题"，只按本次 query 检索；不另加重载。
     */
    @PostMapping("/search")
    public List<SearchItemResponse> search(@RequestBody SearchRequest request) {
        return searchService.hybridSearch(request.query(), null,
                TopKUtils.resolve(request.topK(), SearchConstants.ADMIN_SEARCH_DEFAULT_TOP_K));
    }
}
