package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;

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
}
