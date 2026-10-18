package com.lender.assistant.rag.service;

import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.service.impl.DocumentServiceImpl;
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
