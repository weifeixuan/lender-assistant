package com.lender.assistant.rag.service;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.dto.ChunkDTO;
import com.lender.assistant.rag.model.entity.ChunkItemEntity;
import com.lender.assistant.rag.model.entity.ChunkEntity;
import com.lender.assistant.rag.model.dto.DocChunkLineDTO;
import com.lender.assistant.rag.model.dto.DocContentDTO;
import com.lender.assistant.rag.model.dto.DocSummaryDTO;
import com.lender.assistant.rag.model.dto.ParsedDocumentDTO;
import com.lender.assistant.rag.service.impl.DocumentServiceImpl;
import com.lender.assistant.rag.strategy.ChunkSplitterStrategy;
import com.lender.assistant.rag.strategy.DocumentParserStrategy;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.strategy.factory.DocumentParserStrategyFactory;
import com.lender.assistant.rag.model.response.ChunkItemResponse;
import com.lender.assistant.rag.model.response.ChunkPageResponse;
import com.lender.assistant.rag.model.response.UploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DocumentServiceImpl 单元测试：只测自身编排逻辑，Mapper 全部 mock（§10.2 不连真库）。
 *
 * 断言锚点是"与旧 rag/store/DocumentStore 逐行为等价"：
 * 空文档只删不插、非空文档先删后插、无切片返回 null、【章节】前缀拼接逐字一致。
 */
class DocumentServiceTest {

    private DocChunkMapper mapper;
    private DocumentParserStrategyFactory parserFactory;
    private ChunkSplitterStrategy splitter;
    private EmbeddingStrategy embeddingStrategy;
    /** 自注入代理（判重/写库的验证点） */
    private DocumentService self;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DocChunkMapper.class);
        parserFactory = mock(DocumentParserStrategyFactory.class);
        splitter = mock(ChunkSplitterStrategy.class);
        embeddingStrategy = mock(EmbeddingStrategy.class);
        self = mock(DocumentService.class);
        service = new DocumentServiceImpl(mapper, parserFactory, splitter, embeddingStrategy, self);
    }

    @Test
    @DisplayName("replaceDocument：空 rows 只删旧块，不触发 INSERT（避免空 VALUES 非法 SQL）")
    void replaceDocumentWithEmptyRowsOnlyDeletes() {
        service.replaceDocument("doc1", "测试文档", "/path/x.docx", List.of());

        verify(mapper).deleteByDoc("doc1");
        verify(mapper, never()).insertChunks(any(), any(), any(), any());
    }

    @Test
    @DisplayName("replaceDocument：非空 rows 先删后插，参数原样透传")
    void replaceDocumentWithRowsDeletesThenInserts() {
        List<ChunkEntity> rows = List.of(new ChunkEntity("第1节", 0, "正文A", 3, "[0.1,0.2]"));

        service.replaceDocument("doc1", "测试文档", "/path/x.docx", rows);

        verify(mapper).deleteByDoc("doc1");
        verify(mapper).insertChunks("doc1", "测试文档", "/path/x.docx", rows);
    }

    @Test
    @DisplayName("replaceDocument：docId 为空/超 64 字符/含非法字符时抛 BizException，且不触达 Mapper")
    void replaceDocumentRejectsInvalidDocId() {
        List<ChunkEntity> rows = List.of(new ChunkEntity("第1节", 0, "正文A", 3, "[0.1,0.2]"));
        String tooLong = "a".repeat(65);

        assertThrows(BizException.class,
                () -> service.replaceDocument("", "测试文档", "/path/x.docx", rows), "空 docId 必须拒绝");
        assertThrows(BizException.class,
                () -> service.replaceDocument(tooLong, "测试文档", "/path/x.docx", rows), "超过 64 字符必须拒绝");
        assertThrows(BizException.class,
                () -> service.replaceDocument("bad id", "测试文档", "/path/x.docx", rows),
                "含空格等非法字符必须拒绝");

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("replaceDocument：64 字符边界且字符集合法的 docId 放行")
    void replaceDocumentAcceptsBoundaryDocId() {
        String docId = "a".repeat(64);

        service.replaceDocument(docId, "测试文档", "/path/x.docx", List.of());

        verify(mapper).deleteByDoc(docId);
    }

    @Test
    @DisplayName("listChunkPage：page/size 未传时归一为第 1 页 20 条，offset=0")
    void listChunkPageAppliesDefaults() {
        when(mapper.countByDoc("doc1")).thenReturn(35);
        when(mapper.listChunkPage("doc1", 20, 0)).thenReturn(List.of(
                new ChunkItemEntity(1L, 0, "甲", 10, "正文A"),
                new ChunkItemEntity(2L, 1, "乙", 20, "正文B")));

        ChunkPageResponse page = service.listChunkPage("doc1", null, null);

        assertEquals(1, page.page(), "未传 page 默认第 1 页");
        assertEquals(20, page.size(), "未传 size 默认 20 条");
        assertEquals(35L, page.total(), "total 必须来自 countByDoc");
        assertEquals(2, page.items().size());
        verify(mapper).listChunkPage("doc1", 20, 0);
    }

    @Test
    @DisplayName("listChunkPage：page<1 归一到 1，size 越界夹到 1-100")
    void listChunkPageClampsOutOfRange() {
        when(mapper.countByDoc("doc1")).thenReturn(5);
        when(mapper.listChunkPage("doc1", 100, 0)).thenReturn(List.of());

        ChunkPageResponse low = service.listChunkPage("doc1", 0, 0);
        ChunkPageResponse high = service.listChunkPage("doc1", -3, 1000);

        assertEquals(1, low.page(), "page 小于 1 归一到 1");
        assertEquals(1, low.size(), "size 小于 1 归一到 1");
        assertEquals(100, high.size(), "size 大于 100 夹到上限 100");
        verify(mapper).listChunkPage("doc1", 1, 0);
        verify(mapper).listChunkPage("doc1", 100, 0);
    }

    @Test
    @DisplayName("listChunkPage：docId 不存在抛 DOC_NOT_FOUND，不返回空页装作成功")
    void listChunkPageThrowsWhenDocMissing() {
        when(mapper.countByDoc("missing")).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.listChunkPage("missing", 1, 20));

        assertEquals(ErrorCode.DOC_NOT_FOUND, e.errorCode(), "文档不存在必须是可区分业务码");
        verify(mapper, never()).listChunkPage(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("listChunkPage：第 2 页 offset=(page-1)*size，行映射为响应字段")
    void listChunkPageMapsItemsAndOffset() {
        when(mapper.countByDoc("doc1")).thenReturn(35);
        when(mapper.listChunkPage("doc1", 10, 10)).thenReturn(List.of(
                new ChunkItemEntity(11L, 10, "3 逾期文件 > 3.2 格式", 88, "逾期阶段 M1 为 1-30 天")));

        ChunkPageResponse page = service.listChunkPage("doc1", 2, 10);

        assertEquals(2, page.page());
        assertEquals(10, page.size());
        assertEquals("doc1", page.docId());
        ChunkItemResponse item = page.items().get(0);
        assertEquals(11L, item.chunkId());
        assertEquals(10, item.index(), "index 必须透传 chunk_index");
        assertEquals("3 逾期文件 > 3.2 格式", item.sectionPath());
        assertEquals(88, item.charCount());
        assertEquals("逾期阶段 M1 为 1-30 天", item.content());
        verify(mapper).listChunkPage("doc1", 10, 10);
    }

    @Test
    @DisplayName("ingestDocument：同内容不同 docId 不再新增，返回既有文档且不做向量化")
    void ingestDeduplicatesSameContentUnderDifferentDocId() throws Exception {
        List<ChunkDTO> chunks = List.of(
                new ChunkDTO("第1节", 0, "正文A", 3), new ChunkDTO("第2节", 1, "正文B", 3));
        when(mapper.listDocuments()).thenReturn(List.of(
                new DocSummaryDTO("huaxin_corp_api_v1", "旧标题", "旧路径.docx", 2, 6L, "2026-10-29 22:50:21")));
        when(mapper.listChunkLines("huaxin_corp_api_v1")).thenReturn(List.of(
                new DocChunkLineDTO("旧标题", "第1节", "正文A"),
                new DocChunkLineDTO("旧标题", "第2节", "正文B")));

        UploadResponse response = ingest("new_doc_id", "换了文件名.docx", chunks);

        assertEquals("huaxin_corp_api_v1", response.docId(), "判重时必须返回既有文档的 docId");
        assertEquals(2, response.chunkCount(), "切片数来自既有文档，不新增");
        assertEquals(3.0, response.avgChunkChars());
        verify(embeddingStrategy, never()).embed(any());
        verify(self, never()).replaceDocument(any(), any(), any(), any());
    }

    @Test
    @DisplayName("ingestDocument：切片数/总字符数相同但内容不同，正常向量化并写入")
    void ingestInsertsWhenContentDiffers() throws Exception {
        List<ChunkDTO> chunks = List.of(
                new ChunkDTO("第1节", 0, "正文A", 3), new ChunkDTO("第2节", 1, "正文C", 3));
        when(mapper.listDocuments()).thenReturn(List.of(
                new DocSummaryDTO("huaxin_corp_api_v1", "旧标题", "旧路径.docx", 2, 6L, "2026-10-29 22:50:21")));
        when(mapper.listChunkLines("huaxin_corp_api_v1")).thenReturn(List.of(
                new DocChunkLineDTO("旧标题", "第1节", "正文A"),
                new DocChunkLineDTO("旧标题", "第2节", "正文B")));
        when(embeddingStrategy.embed(any())).thenReturn(new float[]{0.5f, 0.5f});

        UploadResponse response = ingest("new_doc_id", "新文档.docx", chunks);

        assertEquals("new_doc_id", response.docId(), "内容不同则按新文档正常入库");
        assertEquals(2, response.chunkCount());
        verify(self).replaceDocument(eq("new_doc_id"), any(), eq("新文档.docx"), any());
    }

    /** 走完 解析 → 切分 → 判重 的前半段，parser/splitter 用 mock 喂固定切片 */
    private UploadResponse ingest(String docId, String fileName, List<ChunkDTO> chunks) throws Exception {
        ParsedDocumentDTO parsed = new ParsedDocumentDTO(fileName, List.of(ParsedDocumentDTO.Block.paragraph("正文")));
        DocumentParserStrategy parser = mock(DocumentParserStrategy.class);
        when(parserFactory.resolve(fileName)).thenReturn(parser);
        when(parser.parse(eq(fileName), any())).thenReturn(parsed);
        when(splitter.split(parsed)).thenReturn(chunks);
        return service.ingestDocument(docId, fileName, new byte[]{1});
    }

    @Test
    @DisplayName("loadDocContent：mapper 返回空列表时返回 null（文档不存在）")
    void loadDocContentReturnsNullWhenNoChunks() {
        when(mapper.listChunkLines("doc1")).thenReturn(List.of());

        DocContentDTO content = service.loadDocContent("doc1");

        assertNull(content, "无切片视为文档不存在，返回 null 而非空串");
    }

    @Test
    @DisplayName("loadDocContent：按切片序拼接，每块前置【章节】，标题取首行")
    void loadDocContentJoinsChunksWithSectionPrefix() {
        when(mapper.listChunkLines("doc1")).thenReturn(List.of(
                new DocChunkLineDTO("标题", "甲", "正文A"),
                new DocChunkLineDTO("标题", "乙", "正文B")));

        DocContentDTO content = service.loadDocContent("doc1");

        assertNotNull(content);
        assertEquals("标题", content.docTitle(), "同一 doc 各块标题相同，取首行即可");
        assertEquals("【章节】甲\n正文A\n【章节】乙\n正文B", content.text(),
                "拼接规则须与旧 DocumentStore 逐字一致");
    }
}
