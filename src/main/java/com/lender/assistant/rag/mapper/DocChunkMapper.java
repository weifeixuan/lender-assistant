package com.lender.assistant.rag.mapper;

import com.lender.assistant.rag.model.entity.ChunkItemEntity;
import com.lender.assistant.rag.model.entity.ChunkEntity;
import com.lender.assistant.rag.model.dto.DocChunkLineDTO;
import com.lender.assistant.rag.model.dto.DocSummaryDTO;
import com.lender.assistant.rag.model.dto.KeywordHitDTO;
import com.lender.assistant.rag.model.dto.VectorHitDTO;
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
                     @Param("sourcePath") String sourcePath, @Param("rows") List<ChunkEntity> rows);

    int countByDoc(@Param("docId") String docId);

    List<DocSummaryDTO> listDocuments();

    /**
     * 判重候选：SQL 侧先按 (切片数, 总字符数) 收窄，只对形状一致的文档做内容逐行比对，
     * 避免把全库文档和切片都读进内存（禁 SQL 锁，判重仍在代码内串行）。
     */
    List<DocSummaryDTO> listDocumentsByShape(@Param("chunkCount") int chunkCount,
                                             @Param("charCountTotal") long charCountTotal);

    List<DocChunkLineDTO> listChunkLines(@Param("docId") String docId);

    /** 按 chunk_index 分页取切片（不含 embedding）；limit/offset 由 Service 归一后传入 */
    List<ChunkItemEntity> listChunkPage(@Param("docId") String docId,
                                     @Param("limit") int limit, @Param("offset") int offset);

    /** candidateLimit 是候选池大小（SearchConstants.candidateLimit），不是对外返回条数 */
    List<VectorHitDTO> searchByVector(@Param("vec") String vec, @Param("candidateLimit") int candidateLimit);

    /**
     * 调用方保证 tokens 非空（<foreach> 空集合会生成非法 SQL）。
     * candidateLimit 语义同上：候选池大小，最终对外条数由 Service 截断。
     */
    List<KeywordHitDTO> searchByKeyword(@Param("tokens") List<String> tokens,
                                     @Param("candidateLimit") int candidateLimit);
}
