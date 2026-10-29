package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.vo.response.ChunkPageResponse;

import java.util.List;

/**
 * doc_chunk 的领域服务：rag 领域内文档读写的唯一入口。
 *
 * 为什么需要它（§1 依赖方向 controller → service → mapper）：
 * Controller 与跨领域消费者（LenderQueryTools、ExtractServiceImpl）不该直接触碰 Mapper，
 * 否则 SQL 细节会往上漏、跨领域会绕过 service 直连别人的数据层。跨领域只经本接口（service → service）。
 */
public interface DocumentService {

    /** 先删旧块再插新块：同一 docId 重复入库幂等；删+插必须原子（事务在实现方法上） */
    void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows);

    int countByDoc(String docId);

    void deleteByDoc(String docId);

    List<DocSummary> listDocuments();

    /** 按 chunk 序拼全文（每块前置一行【章节】section_path）；文档不存在返回 null */
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
