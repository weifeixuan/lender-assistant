package com.lender.assistant.extract;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * field_mapping 表读写：抽取结果落库 + 人工确认状态机。
 *
 * 状态流转（手册"人工在界面上确认/修正"的落点）：
 * PENDING（模型抽出，待确认）→ CONFIRMED（人工点头）→ CORRECTED（人工改过值）
 * upsert 语义：同一文档重复抽取覆盖旧结果，但只覆盖仍为 PENDING 的行——
 * 人工已确认的行不被重抽冲掉（ON CONFLICT 里用 WHERE status = 'PENDING' 守住）。
 */
@Repository
public class FieldMappingStore {

    private static final String UPSERT = """
            INSERT INTO field_mapping
                (doc_id, internal_field_id, lender_field_name, lender_value, source_quote, confidence, status)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (doc_id, internal_field_id) DO UPDATE
                SET lender_field_name = EXCLUDED.lender_field_name,
                    lender_value      = EXCLUDED.lender_value,
                    source_quote      = EXCLUDED.source_quote,
                    confidence        = EXCLUDED.confidence,
                    status            = 'PENDING'
                WHERE field_mapping.status = 'PENDING'
            """;

    private final JdbcTemplate jdbc;

    public FieldMappingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int saveMappings(String docId, List<FieldMapping> mappings) {
        int n = 0;
        for (FieldMapping m : mappings) {
            n += jdbc.update(UPSERT, docId, m.internalFieldId(), m.lenderFieldName(),
                    m.lenderValue(), m.sourceQuote(), m.confidence());
        }
        return n;
    }

    public List<Map<String, Object>> listMappings(String docId) {
        return jdbc.query("""
                SELECT id, doc_id, internal_field_id, lender_field_name, lender_value,
                       source_quote, confidence, status, created_at
                FROM field_mapping
                WHERE doc_id = ?
                ORDER BY internal_field_id
                """,
                (rs, i) -> Map.of(
                        "id", rs.getLong("id"),
                        "docId", rs.getString("doc_id"),
                        "internalFieldId", rs.getString("internal_field_id"),
                        "lenderFieldName", rs.getString("lender_field_name") == null ? "" : rs.getString("lender_field_name"),
                        "lenderValue", rs.getString("lender_value") == null ? "" : rs.getString("lender_value"),
                        "sourceQuote", rs.getString("source_quote") == null ? "" : rs.getString("source_quote"),
                        "confidence", rs.getDouble("confidence"),
                        "status", rs.getString("status")),
                docId);
    }

    /**
     * 人工确认/修正。corrected 值非空时状态记 CORRECTED（留痕"人改过"），
     * 为空则只确认原文 CONFIRMED。
     */
    public void confirm(String docId, String internalFieldId, String correctedFieldName, String correctedValue) {
        if (correctedValue != null && !correctedValue.isBlank()) {
            jdbc.update("""
                    UPDATE field_mapping
                    SET lender_value = COALESCE(?, lender_value),
                        lender_field_name = COALESCE(?, lender_field_name),
                        status = 'CORRECTED'
                    WHERE doc_id = ? AND internal_field_id = ?
                    """, correctedValue, correctedFieldName, docId, internalFieldId);
        } else {
            jdbc.update("UPDATE field_mapping SET status = 'CONFIRMED' WHERE doc_id = ? AND internal_field_id = ?",
                    docId, internalFieldId);
        }
    }
}
