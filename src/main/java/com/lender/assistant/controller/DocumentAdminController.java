package com.lender.assistant.controller;

import com.lender.assistant.rag.SearchService;
import com.lender.assistant.rag.embedder.EmbeddingClient;
import com.lender.assistant.rag.parser.DocumentParser;
import com.lender.assistant.rag.splitter.Chunk;
import com.lender.assistant.rag.splitter.ChunkSplitter;
import com.lender.assistant.rag.store.DocumentStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档入库与检索的管理接口（阶段 1 的操作台）。
 *
 * 入库是"流水线"编排：解析 -> 切分 -> 向量化 -> 原子替换入库，
 * 每一步的产出统计都返回给调用方——阶段 3 调优对比实验就靠这些统计数字。
 */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentAdminController {

    private final List<DocumentParser> parsers;
    private final ChunkSplitter splitter;
    private final EmbeddingClient embeddingClient;
    private final DocumentStore store;
    private final SearchService searchService;

    public DocumentAdminController(List<DocumentParser> parsers, ChunkSplitter splitter,
                                   EmbeddingClient embeddingClient, DocumentStore store,
                                   SearchService searchService) {
        this.parsers = parsers;
        this.splitter = splitter;
        this.embeddingClient = embeddingClient;
        this.store = store;
        this.searchService = searchService;
    }

    /**
     * 上传并入库。docId 由调用方指定（如 huaxin_corp_api_v1），
     * 重复上传同一 docId = 覆盖重建，方便反复实验。
     */
    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam("docId") String docId) throws Exception {
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的文件类型: " + fileName
                        + "（当前支持 .docx/.txt/.md）"));

        long t0 = System.currentTimeMillis();
        var parsed = parser.parse(fileName, file.getBytes());
        long t1 = System.currentTimeMillis();
        List<Chunk> chunks = splitter.split(parsed);
        long t2 = System.currentTimeMillis();

        List<DocumentStore.ChunkRow> rows = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            // 拼接 sectionPath 进向量化输入：标题是强语义信号，丢掉太可惜
            String embedInput = c.sectionPath() == null || c.sectionPath().isEmpty()
                    ? c.content()
                    : c.sectionPath() + "\n" + c.content();
            rows.add(new DocumentStore.ChunkRow(c.sectionPath(), c.index(), c.content(),
                    c.charCount(), embeddingClient.embed(embedInput)));
        }
        long t3 = System.currentTimeMillis();

        store.replaceDocument(docId, stripExtension(fileName), fileName, rows);
        long t4 = System.currentTimeMillis();

        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("blocks", parsed.blocks().size());
        result.put("chunkCount", rows.size());
        result.put("avgChunkChars", rows.stream().mapToInt(DocumentStore.ChunkRow::charCount).average().orElse(0));
        result.put("millis", Map.of("parse", t1 - t0, "split", t2 - t1, "embed", t3 - t2, "store", t4 - t3));
        result.put("embeddingProvider", embeddingClient.getClass().getSimpleName());
        return result;
    }

    @GetMapping
    public List<DocumentStore.DocSummary> list() {
        return store.listDocuments();
    }

    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        int before = store.countByDoc(docId);
        store.deleteByDoc(docId);
        return Map.of("docId", docId, "deletedChunks", before);
    }

    /** 检索测试入口（阶段 2 会挪到面向问答的接口上） */
    @PostMapping("/search")
    public List<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int topK = body.get("topK") == null ? 5 : (Integer) body.get("topK");
        return searchService.hybridSearch(query, topK);
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
