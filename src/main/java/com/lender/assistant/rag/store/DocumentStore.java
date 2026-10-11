package com.lender.assistant.rag.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * doc_chunk 表的读写。
 *
 * 技术选型：JdbcTemplate + 手写 SQL，不用 JPA——
 * 本项目大量使用 pgvector 专属语法（::vector 类型转换、<=> 余弦距离操作符、
 * HNSW 索引），JPA 对这类 PG 扩展支持贫瘠，手写 SQL 更直观可控。
 *
 * 向量怎么进 SQL：pgvector 接受文本形式的向量字面量 "[0.1,0.2,...]"，
 * 拼进参数时用 ?::vector 显式转换（PG 面向参数的类型推断对自定义类型较弱）。
 */
@Repository
public class DocumentStore {

    private final JdbcTemplate jdbc;

    public DocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 先删旧块再插新块：同一 doc_id 重复入库不会留脏数据（幂等） */
    public void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows) {
        jdbc.update("DELETE FROM doc_chunk WHERE doc_id = ?", docId);
        for (ChunkRow row : rows) {
            jdbc.update("""
                    INSERT INTO doc_chunk
                        (doc_id, doc_title, source_path, section_path, chunk_index, content, char_count, embedding)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)
                    """,
                    docId, docTitle, sourcePath, row.sectionPath(), row.index(),
                    row.content(), row.charCount(), toPgVector(row.embedding()));
        }
    }

    public int countByDoc(String docId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM doc_chunk WHERE doc_id = ?", Integer.class, docId);
        return n == null ? 0 : n;
    }

    public void deleteByDoc(String docId) {
        jdbc.update("DELETE FROM doc_chunk WHERE doc_id = ?", docId);
    }

    public record DocSummary(String docId, String docTitle, int chunkCount) {}

    public List<DocSummary> listDocuments() {
        return jdbc.query(
                "SELECT doc_id, doc_title, COUNT(*) AS chunk_count FROM doc_chunk GROUP BY doc_id, doc_title ORDER BY doc_id",
                (rs, i) -> new DocSummary(rs.getString("doc_id"), rs.getString("doc_title"), rs.getInt("chunk_count")));
    }

    /**
     * 向量召回：余弦距离 <=> 越小越相似，按距离升序取 topK。
     * HNSW 索引在这里生效。
     */
    public record VectorHit(long id, String docId, String docTitle, String sectionPath, String content, double distance) {}

    public List<VectorHit> searchByVector(float[] embedding, int topK) {
        return jdbc.query("""
                SELECT id, doc_id, doc_title, section_path, content,
                       embedding <=> ?::vector AS distance
                FROM doc_chunk
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, i) -> new VectorHit(rs.getLong("id"), rs.getString("doc_id"), rs.getString("doc_title"),
                        rs.getString("section_path"), rs.getString("content"), rs.getDouble("distance")),
                toPgVector(embedding), toPgVector(embedding), topK);
    }

    /**
     * 关键词召回：trigram 相似度 %（0~1，越大越像），
     * 对"必须命中字面"的查询（如"逾期文件第3列"）比向量更可靠。
     */
    public record KeywordHit(long id, String docId, String docTitle, String sectionPath, String content, double similarity) {}

    public List<KeywordHit> searchByKeyword(String query, int topK) {
        return jdbc.query("""
                SELECT id, doc_id, doc_title, section_path, content,
                       SIMILARITY(content, ?) AS similarity
                FROM doc_chunk
                WHERE content % ? OR content LIKE '%' || ? || '%'
                ORDER BY similarity DESC
                LIMIT ?
                """,
                (rs, i) -> new KeywordHit(rs.getLong("id"), rs.getString("doc_id"), rs.getString("doc_title"),
                        rs.getString("section_path"), rs.getString("content"), rs.getDouble("similarity")),
                query, query, query, topK);
    }

    /** float[] -> pgvector 文本字面量 "[0.1,0.2,...]" */
    static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    public record ChunkRow(String sectionPath, int index, String content, int charCount, float[] embedding) {}
}
