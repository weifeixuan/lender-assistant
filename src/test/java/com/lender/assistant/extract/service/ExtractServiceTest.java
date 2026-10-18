package com.lender.assistant.extract.service;

import com.lender.assistant.extract.mapper.FieldMappingMapper;
import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.model.FieldMappingRaw;
import com.lender.assistant.extract.model.FieldMappingRow;
import com.lender.assistant.extract.service.impl.ExtractServiceImpl;
import com.lender.assistant.resilience.LlmCallLogger;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ExtractService 编排测试：加载文档 → 拼 text → 经留痕调生成器。
 * 生成器本身已在上一个测试类验证过，这里用桩锁定编排契约。
 * 映射表读写（原 FieldMappingStoreTest 的行为契约）也并入本类——数据访问切到 Mapper 后，
 * 测试从 mock JdbcTemplate 改为 mock FieldMappingMapper。
 */
class ExtractServiceTest {

    private ExtractGenerator generator;
    private DocumentService documentService;
    private FieldMappingMapper mapper;
    private ResilientLlmExecutor executor;
    private ExtractService service;

    @BeforeEach
    void setUp() {
        generator = mock(ExtractGenerator.class);
        documentService = mock(DocumentService.class);
        mapper = mock(FieldMappingMapper.class);
        // 真实韧性执行器（chaos=false 直通）+ mock JDBC 的留痕器：
        // 编排契约（supplier 真执行、异常真上抛）必须走真实代码路径
        executor = spy(new ResilientLlmExecutor(
                new LlmCallLogger(mock(LlmCallLogMapper.class)), 3, 10, 2, false));
        service = new ExtractServiceImpl(generator, documentService, mapper, executor);

        when(generator.name()).thenReturn("Mock");
        when(generator.extract(anyString(), anyString())).thenReturn(List.of(
                new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                        "https://fake-x.example.com", "网关地址 https://fake-x.example.com", 0.9)));
        when(documentService.loadDocContent("doc1")).thenReturn(
                new DocContent("瑞金租赁_资金对接接口文档_v1.0",
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
        // 生成器调用必须经韧性执行器包装，scene=extract
        verify(executor).execute(eq("extract"), eq("Mock"), any(Supplier.class), eq(true));
    }

    @Test
    @DisplayName("生成器抛异常：留痕记 FAIL 后异常上抛（由 Controller 转降级响应）")
    void generatorFailureIsLoggedAndRethrown() {
        when(generator.extract(anyString(), anyString()))
                .thenThrow(new RuntimeException("模拟抽取超时"));

        assertThrows(RuntimeException.class, () -> service.extractFromDoc("doc1"));
        verify(executor).execute(eq("extract"), eq("Mock"), any(Supplier.class), eq(true));
    }

    @Test
    @DisplayName("文档不存在：给出可读异常而非 NPE")
    void missingDocGivesReadableError() {
        when(documentService.loadDocContent("nope")).thenReturn(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.extractFromDoc("nope"));
        assertTrue(e.getMessage().contains("nope"));
    }

    private FieldMapping sampleMapping() {
        return new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                "https://fake-ruijin.example.com/openapi", "网关地址 https://fake-ruijin.example.com/openapi", 0.9);
    }

    @Test
    @DisplayName("落库：先清 stale PENDING 再批量 upsert，两步顺序与参数正确")
    void saveMappingsCleansStaleThenUpserts() {
        when(mapper.deleteStalePending("doc1")).thenReturn(0);
        when(mapper.upsertAll(anyList())).thenReturn(1);

        int n = service.saveMappings("doc1", List.of(sampleMapping()));

        assertEquals(1, n);
        // 顺序断言：必须先清理再覆盖，否则消失的字段会残留成脏数据
        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).deleteStalePending("doc1");
        inOrder.verify(mapper).upsertAll(anyList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldMappingRaw>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper).upsertAll(captor.capture());
        FieldMappingRaw raw = captor.getValue().get(0);
        assertEquals("doc1", raw.docId());
        assertEquals("gateway_url", raw.internalFieldId());
        assertEquals(0.9, raw.confidence(), 1e-9);
    }

    @Test
    @DisplayName("落库：空映射表只清 stale，不触发 upsert（避免空 VALUES）")
    void saveMappingsEmptySkipsUpsert() {
        service.saveMappings("doc1", List.of());

        verify(mapper).deleteStalePending("doc1");
        verify(mapper, never()).upsertAll(anyList());
    }

    @Test
    @DisplayName("确认/修正：带修正值走 CORRECTED，空修正值走 CONFIRMED（人工成果状态机）")
    void confirmBranchesByCorrectedValue() {
        service.confirm("doc1", "gateway_url", "新标签", "https://new.example.com");
        verify(mapper).confirmCorrected("doc1", "gateway_url", "新标签", "https://new.example.com");
        verify(mapper, never()).confirmAsIs(anyString(), anyString());

        service.confirm("doc1", "gateway_url", null, null);
        verify(mapper).confirmAsIs("doc1", "gateway_url");
    }

    @Test
    @DisplayName("类型化查询：内部字段名由字段目录补全，未知 id 回退为 id 本身")
    void listFieldMappingsFillsNameFromCatalog() {
        when(mapper.listForDraft("doc1")).thenReturn(List.of(
                new FieldMappingRaw("doc1", "gateway_url", "网关地址", "https://x", "网关地址 https://x", 0.9),
                new FieldMappingRaw("doc1", "no_such_field", "x", "y", "z", 0.7)));

        List<FieldMapping> result = service.listFieldMappings("doc1");

        assertEquals("生产网关地址", result.get(0).internalFieldName(), "目录里的字段必须补出中文名");
        assertEquals("no_such_field", result.get(1).internalFieldName(), "目录查不到时回退为 id，不丢数据");
    }

    @Test
    @DisplayName("映射列表：原样返回带状态的行（JSON 契约载体）")
    void listMappingsReturnsRowsAsIs() {
        when(mapper.listRows("doc1")).thenReturn(List.of(
                new FieldMappingRow(1L, "doc1", "gateway_url", "", "https://x", "", 0.9, "PENDING")));

        List<FieldMappingRow> rows = service.listMappings("doc1");

        assertEquals(1, rows.size());
        assertEquals("PENDING", rows.get(0).status());
        assertEquals("gateway_url", rows.get(0).internalFieldId());
    }
}
