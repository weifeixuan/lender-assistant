package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.vo.response.ChunkPageResponse;
import com.lender.assistant.rag.vo.response.UploadResponse;

import java.util.List;

/**
 * doc_chunk 的领域服务：rag 领域内文档读写的唯一入口。
 *
 * 为什么需要它（§1 依赖方向 controller → service → mapper）：
 * Controller 与跨领域消费者（LenderQueryTools、ExtractServiceImpl）不该直接触碰 Mapper，
 * 否则 SQL 细节会往上漏、跨领域会绕过 service 直连别人的数据层。跨领域只经本接口（service → service）。
 */
public interface DocumentService {

    /**
     * 上传入库流水线：解析 → 切分 → 按内容判重 → 向量化 → 原子替换写入。
     * 解析/切分/向量化是长耗时操作，不放在事务里；只有最后一步写入经 {@link #replaceDocument} 走事务。
     *
     * 判重语义：以 docId=X 上传内容 C、而 C 已在库中时，返回的是**既有内容的 docId**（可能不等于 X）；
     * 该 docId 对应的既有文档既不会被本调用创建、也不会被覆盖（向量化与写库都跳过）。
     */
    UploadResponse ingestDocument(String docId, String fileName, byte[] fileBytes) throws Exception;

    /** 先删旧块再插新块：同一 docId 重复入库幂等；删+插必须原子（事务在实现方法上） */
    void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows);

    int countByDoc(String docId);

    void deleteByDoc(String docId);

    List<DocSummary> listDocuments();

    /**
     * 按 chunk 序取文档内容：text 为拼好的全文（每块前置一行【章节】section_path，供模型 Prompt），
     * lines 为结构化 chunk 行（供规则抽取按章节分类）；文档不存在返回 null。
     */
    DocContent loadDocContent(String docId);

    /**
     * 切片分页查询：page 从 1 开始、size 1-100，未传与越界都在本方法归一；
     * docId 不存在抛 BizException(DOC_NOT_FOUND)，不返回空页装作成功。
     */
    ChunkPageResponse listChunkPage(String docId, Integer page, Integer size);

    /**
     * docId 合法性校验（非空、≤64 字符、仅 [A-Za-z0-9_-]），非法抛 BizException(PARAM_INVALID)。
     * 入库等耗时操作前先调本方法，避免白跑解析/向量化；{@link #replaceDocument} 内部也会兜底校验。
     */
    void validateDocId(String docId);
}
