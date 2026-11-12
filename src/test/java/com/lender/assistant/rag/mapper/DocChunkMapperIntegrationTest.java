package com.lender.assistant.rag.mapper;

import com.lender.assistant.common.util.PgVectorUtils;
import com.lender.assistant.rag.model.dto.DocChunkLineDTO;
import com.lender.assistant.rag.model.dto.DocSummaryDTO;
import com.lender.assistant.rag.model.dto.KeywordHitDTO;
import com.lender.assistant.rag.model.dto.VectorHitDTO;
import com.lender.assistant.rag.model.entity.ChunkEntity;
import com.lender.assistant.testutil.PostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * doc_chunk 的真实 SQL 验证（Testcontainers + pgvector 镜像）。
 *
 * <p>单测里的 Mapper 断言只能看 XML 文本（如 {@code ConversationMapperXmlContractTest}），
 * 这里才真正执行 {@code CAST(... AS vector)}、{@code <=>}、{@code HAVING}——
 * 这些语法写错时编译期与纯单测都发现不了。</p>
 *
 * <p>每个用例 {@code @Transactional} 回滚，用例之间互不污染。</p>
 */
@Tag("docker")
@Transactional
class DocChunkMapperIntegrationTest extends PostgresContainerSupport {

    /** 与 docs/schema.sql 的 vector(1024) 对齐 */
    private static final int DIMENSION = 1024;

    @Autowired
    private DocChunkMapper mapper;

    @Test
    void insertAndReadBackChunksInIndexOrder() {
        mapper.insertChunks("it-order", "顺序测试", "it-order.docx", List.of(
                chunk(1, "第二块", 3, unitVector(1)),
                chunk(0, "第一块", 3, unitVector(0))));

        assertThat(mapper.countByDoc("it-order")).isEqualTo(2);
        // 插入顺序是 1、0，取出必须按 chunk_index 升序
        assertThat(mapper.listChunkLines("it-order"))
                .extracting(DocChunkLineDTO::content)
                .containsExactly("第一块", "第二块");
    }

    @Test
    void searchByVectorOrdersByCosineDistance() {
        mapper.insertChunks("it-near", "近邻", "it-near.docx",
                List.of(chunk(0, "与查询同向", 5, unitVector(0))));
        mapper.insertChunks("it-far", "远端", "it-far.docx",
                List.of(chunk(0, "与查询反向", 5, oppositeVector(0))));

        List<VectorHitDTO> hits = mapper.searchByVector(unitVector(0), 10);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).docId()).isEqualTo("it-near");
        assertThat(hits.get(0).distance()).isCloseTo(0.0, offset(1e-6));
        assertThat(hits.get(1).docId()).isEqualTo("it-far");
        assertThat(hits.get(1).distance()).isCloseTo(2.0, offset(1e-6));
    }

    @Test
    void searchByVectorHonorsCandidateLimit() {
        mapper.insertChunks("it-limit-a", "A", "a.docx", List.of(chunk(0, "A", 1, unitVector(0))));
        mapper.insertChunks("it-limit-b", "B", "b.docx", List.of(chunk(0, "B", 1, unitVector(1))));
        mapper.insertChunks("it-limit-c", "C", "c.docx", List.of(chunk(0, "C", 1, unitVector(2))));

        assertThat(mapper.searchByVector(unitVector(0), 2)).hasSize(2);
    }

    @Test
    void searchByKeywordRanksByHitCount() {
        mapper.insertChunks("it-kw", "关键词", "kw.docx", List.of(
                chunk(0, "逾期文件 第3列 格式说明", 14, unitVector(0)),
                chunk(1, "逾期文件的处理流程", 12, unitVector(1)),
                chunk(2, "完全无关的内容", 7, unitVector(2))));

        List<KeywordHitDTO> hits = mapper.searchByKeyword(List.of("逾期", "第3列"), 10);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).hits()).isEqualTo(2);
        assertThat(hits.get(0).content()).contains("第3列");
        assertThat(hits.get(1).hits()).isEqualTo(1);
    }

    @Test
    void listDocumentsByShapeNarrowsCandidatesInSql() {
        mapper.insertChunks("it-shape-a", "形状A", "a.docx", List.of(
                chunk(0, "aaa", 3, unitVector(0)),
                chunk(1, "bbb", 3, unitVector(1))));
        mapper.insertChunks("it-shape-b", "形状B", "b.docx", List.of(
                chunk(0, "ccccc", 5, unitVector(0)),
                chunk(1, "ddddd", 5, unitVector(1))));

        // 形状 = (切片数 2, 总字符 6)：只应命中 A，B 的总字符是 10
        assertThat(mapper.listDocumentsByShape(2, 6))
                .extracting(DocSummaryDTO::docId)
                .containsExactly("it-shape-a");
    }

    @Test
    void deleteByDocRemovesAllChunks() {
        mapper.insertChunks("it-del", "删除", "del.docx", List.of(
                chunk(0, "x", 1, unitVector(0)),
                chunk(1, "y", 1, unitVector(1))));

        assertThat(mapper.deleteByDoc("it-del")).isEqualTo(2);
        assertThat(mapper.countByDoc("it-del")).isZero();
    }

    private static ChunkEntity chunk(int index, String content, int charCount, String embedding) {
        return new ChunkEntity("1 测试章节", index, content, charCount, embedding);
    }

    private static String unitVector(int axis) {
        float[] vector = new float[DIMENSION];
        vector[axis] = 1f;
        return PgVectorUtils.toLiteral(vector);
    }

    private static String oppositeVector(int axis) {
        float[] vector = new float[DIMENSION];
        vector[axis] = -1f;
        return PgVectorUtils.toLiteral(vector);
    }

    private static org.assertj.core.data.Offset<Double> offset(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
