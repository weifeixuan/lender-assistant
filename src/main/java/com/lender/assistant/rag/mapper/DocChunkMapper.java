package com.lender.assistant.rag.mapper;

import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.model.KeywordHit;
import com.lender.assistant.rag.model.VectorHit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * doc_chunk 表的唯一读写落点（§6.1：SQL 只存在于 mapper 层）。
 *
 * 约定：
 * - pgvector 专属语法（CAST(... AS vector)、<=>）只出现在 XML（§6.7）；
 *   向量以字面量字符串传入，由调用方经 PgVectorUtils 转换。
 * - 无 @Transactional（§6.11）；事务边界在 DocumentServiceImpl。
 * - 结果一律映射为 rag/model 的 record，不返回 Map<String,Object>（§6.6/§13）。
 */
@Mapper
public interface DocChunkMapper {

    int deleteByDoc(@Param("docId") String docId);

    /** 单条多行 INSERT（一次往返）；调用方保证 rows 非空 */
    int insertChunks(@Param("docId") String docId, @Param("docTitle") String docTitle,
                     @Param("sourcePath") String sourcePath, @Param("rows") List<ChunkRow> rows);

    int countByDoc(@Param("docId") String docId);

    List<DocSummary> listDocuments();

    List<DocChunkLine> listChunkLines(@Param("docId") String docId);

    List<VectorHit> searchByVector(@Param("vec") String vec, @Param("topK") int topK);

    /** 调用方保证 tokens 非空（<foreach> 空集合会生成非法 SQL） */
    List<KeywordHit> searchByKeyword(@Param("tokens") List<String> tokens, @Param("topK") int topK);
}
