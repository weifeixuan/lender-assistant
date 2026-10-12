package com.lender.assistant.extract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FieldMappingStore 测试：抽取结果落库 + 人工确认状态机（PENDING→CONFIRMED/CORRECTED）。
 * JdbcTemplate mock 掉，验证 SQL 形态与参数；真库行为由启动后 curl 全链路覆盖。
 */
class FieldMappingStoreTest {

    private JdbcTemplate jdbc;
    private FieldMappingStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new FieldMappingStore(jdbc);
    }

    private FieldMapping sample() {
        return new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                "https://fake-ruijin.example.com/openapi", "网关地址 https://fake-ruijin.example.com/openapi", 0.9);
    }

    @Test
    @DisplayName("保存映射：INSERT 走 upsert，重复抽取同一文档不炸唯一约束")
    void saveUsesUpsert() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        int n = store.saveMappings("doc1", List.of(sample()));

        assertEquals(1, n);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        String insertSql = sql.getAllValues().stream()
                .filter(s -> s.toUpperCase().contains("INSERT"))
                .findFirst().orElse("");
        assertTrue(insertSql.contains("field_mapping"), "必须有 INSERT 语句");
        assertTrue(insertSql.toUpperCase().contains("ON CONFLICT"), "必须 upsert，同 doc 重抽不能撞唯一键");
        assertTrue(insertSql.toUpperCase().contains("PENDING"), "初始状态必须是 PENDING 待人工确认");
    }

    @Test
    @DisplayName("重复抽取：先清理该文档残留的 PENDING 旧映射（消失的字段不留脏数据），不动人工确认行")
    void resaveCleansStalePendingOnly() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.saveMappings("doc1", List.of(sample()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        String deleteSql = sql.getAllValues().stream()
                .filter(s -> s.toUpperCase().startsWith("DELETE"))
                .findFirst().orElse("");
        assertFalse(deleteSql.isEmpty(), "重抽前必须有清理语句，否则消失的字段映射残留成脏数据");
        assertTrue(deleteSql.contains("field_mapping"));
        assertTrue(deleteSql.toUpperCase().contains("PENDING"),
                "清理只能针对 PENDING 行——CONFIRMED/CORRECTED 的人工成果不能被重抽冲掉");
        assertTrue(deleteSql.contains("doc_id = ?"), "清理范围必须限定在当前文档");
    }

    @Test
    @DisplayName("确认映射：更新状态为 CONFIRMED，可带修正值")
    void confirmUpdatesStatus() {
        store.confirm("doc1", "gateway_url", null, null);
        verify(jdbc).update(contains("CONFIRMED"), eq("doc1"), eq("gateway_url"));
    }

    @Test
    @DisplayName("按文档查映射列表：返回带状态字段的完整行")
    void listReturnsRows() {
        Map<String, Object> row = Map.of(
                "internal_field_id", "gateway_url",
                "lender_value", "https://fake-ruijin.example.com/openapi",
                "status", "PENDING");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("doc1")))
                .thenReturn(List.of(row));

        List<Map<String, Object>> result = store.listMappings("doc1");

        assertEquals(1, result.size());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("doc1"));
        assertTrue(sql.getValue().contains("field_mapping"));
        assertTrue(sql.getValue().contains("ORDER BY"));
    }
}
