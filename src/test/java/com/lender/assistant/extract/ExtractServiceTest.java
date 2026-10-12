package com.lender.assistant.extract;

import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.rag.store.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ExtractService 编排测试：加载文档 → 拼 text → 经留痕调生成器。
 * 生成器本身已在上一个测试类验证过，这里用桩锁定编排契约。
 */
class ExtractServiceTest {

    private ExtractGenerator generator;
    private DocumentStore store;
    private LlmCallLogger logger;
    private ExtractService service;

    @BeforeEach
    void setUp() {
        generator = mock(ExtractGenerator.class);
        store = mock(DocumentStore.class);
        // 关键：用真实留痕器（supplier 真正执行、异常真正上抛）+ mock 掉 JDBC；
        // 若把 logger 整个 mock 掉，record() 变空壳，编排契约根本测不到
        logger = spy(new LlmCallLogger(mock(JdbcTemplate.class)));
        service = new ExtractService(generator, store, logger);

        when(generator.name()).thenReturn("Mock");
        when(generator.extract(anyString(), anyString())).thenReturn(List.of(
                new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                        "https://fake-x.example.com", "网关地址 https://fake-x.example.com", 0.9)));
        when(store.loadDocContent("doc1")).thenReturn(
                new DocumentStore.DocContent("瑞金租赁_资金对接接口文档_v1.0",
                        "网关地址 https://fake-ruijin.example.com/openapi。\n接口路径 /openapi/loan/order/create。"));
    }

    @Test
    @DisplayName("正常抽取：文档按 chunk 序拼成全文，生成器收到标题+全文")
    void orchestratesLoadAndExtract() {
        List<FieldMapping> result = service.extractFromDoc("doc1");

        assertEquals(1, result.size());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(generator).extract(title.capture(), text.capture());
        assertEquals("瑞金租赁_资金对接接口文档_v1.0", title.getValue());
        assertTrue(text.getValue().contains("网关地址 https://fake-ruijin.example.com/openapi"));
        assertTrue(text.getValue().contains("接口路径 /openapi/loan/order/create"));
        // 生成器调用必须经留痕器包装，scene=extract
        verify(logger).record(eq("extract"), eq("Mock"), any(Supplier.class));
    }

    @Test
    @DisplayName("生成器抛异常：留痕记 FAIL 后异常上抛（由 Controller 转降级响应）")
    void generatorFailureIsLoggedAndRethrown() {
        when(generator.extract(anyString(), anyString()))
                .thenThrow(new RuntimeException("模拟抽取超时"));

        assertThrows(RuntimeException.class, () -> service.extractFromDoc("doc1"));
        verify(logger).record(eq("extract"), eq("Mock"), any(Supplier.class));
    }

    @Test
    @DisplayName("文档不存在：给出可读异常而非 NPE")
    void missingDocGivesReadableError() {
        when(store.loadDocContent("nope")).thenReturn(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.extractFromDoc("nope"));
        assertTrue(e.getMessage().contains("nope"));
    }
}
