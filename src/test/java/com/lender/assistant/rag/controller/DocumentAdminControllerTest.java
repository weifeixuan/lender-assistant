package com.lender.assistant.rag.controller;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.service.SearchService;
import com.lender.assistant.rag.service.impl.DocumentServiceImpl;
import com.lender.assistant.rag.strategy.ChunkSplitterStrategy;
import com.lender.assistant.rag.strategy.EmbeddingStrategy;
import com.lender.assistant.rag.strategy.factory.DocumentParserStrategyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 上传入口的快速失败契约：非法 docId 必须在解析/切分/向量化之前被拒绝，
 * 否则会白跑一次文件解析和一批外部向量化调用才报错，既慢又浪费额度。
 */
class DocumentAdminControllerTest {

    @Test
    @DisplayName("upload：非法 docId 在解析与向量化之前直接抛 BizException")
    void uploadRejectsInvalidDocIdBeforeParsing() throws Exception {
        DocumentParserStrategyFactory parserFactory = mock(DocumentParserStrategyFactory.class);
        ChunkSplitterStrategy splitter = mock(ChunkSplitterStrategy.class);
        EmbeddingStrategy embeddingStrategy = mock(EmbeddingStrategy.class);
        DocChunkMapper mapper = mock(DocChunkMapper.class);
        DocumentAdminController controller = new DocumentAdminController(parserFactory, splitter,
                embeddingStrategy, new DocumentServiceImpl(mapper), mock(SearchService.class));

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("bad.docx");

        assertThrows(BizException.class, () -> controller.upload(file, "bad id"),
                "非法 docId 必须快速失败");

        verifyNoInteractions(parserFactory, splitter, embeddingStrategy, mapper);
    }
}
