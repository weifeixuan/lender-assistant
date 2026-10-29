package com.lender.assistant.rag.service;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.ChunkItemRow;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.service.impl.DocumentServiceImpl;
import com.lender.assistant.rag.vo.response.ChunkItemResponse;
import com.lender.assistant.rag.vo.response.ChunkPageResponse;
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
 * 空文档只删不插、非空文档先删后插、无 chunk 返回 null、【章节】前缀拼接逐字一致。
 */
class DocumentServiceTest {

    private DocChunkMapper mapper;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DocChunkMapper.class);
        service = new DocumentServiceImpl(mapper);
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
        List<ChunkRow> rows = List.of(new ChunkRow("第1节", 0, "正文A", 3, "[0.1,0.2]"));

        service.replaceDocument("doc1", "测试文档", "/path/x.docx", rows);

        verify(mapper).deleteByDoc("doc1");
        verify(mapper).insertChunks("doc1", "测试文档", "/path/x.docx", rows);
    }

    @Test
    @DisplayName("replaceDocument：docId 为空/超 64 字符/含非法字符时抛 BizException，且不触达 Mapper")
    void replaceDocumentRejectsInvalidDocId() {
        List<ChunkRow> rows = List.of(new ChunkRow("第1节", 0, "正文A", 3, "[0.1,0.2]"));
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
                new ChunkItemRow(1L, 0, "甲", 10, "正文A"),
                new ChunkItemRow(2L, 1, "乙", 20, "正文B")));

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
                new ChunkItemRow(11L, 10, "3 逾期文件 > 3.2 格式", 88, "逾期阶段 M1 为 1-30 天")));

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
    @DisplayName("loadDocContent：mapper 返回空列表时返回 null（文档不存在）")
    void loadDocContentReturnsNullWhenNoChunks() {
        when(mapper.listChunkLines("doc1")).thenReturn(List.of());

        DocContent content = service.loadDocContent("doc1");

        assertNull(content, "无 chunk 视为文档不存在，返回 null 而非空串");
    }

    @Test
    @DisplayName("loadDocContent：按 chunk 序拼接，每块前置【章节】，标题取首行")
    void loadDocContentJoinsChunksWithSectionPrefix() {
        when(mapper.listChunkLines("doc1")).thenReturn(List.of(
                new DocChunkLine("标题", "甲", "正文A"),
                new DocChunkLine("标题", "乙", "正文B")));

        DocContent content = service.loadDocContent("doc1");

        assertNotNull(content);
        assertEquals("标题", content.docTitle(), "同一 doc 各块标题相同，取首行即可");
        assertEquals("【章节】甲\n正文A\n【章节】乙\n正文B", content.text(),
                "拼接规则须与旧 DocumentStore 逐字一致");
    }
}
