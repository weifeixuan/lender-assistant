package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.entity.FieldMappingEntity;
import com.lender.assistant.rag.model.dto.DocContentDTO;

import java.util.List;

/**
 * 结构化抽取模型调用服务：新资方文档内容进 → 内部字段映射表出。
 *
 * 入参是结构化文档内容：只用 text 拼 Prompt。
 */
public interface ExtractGeneratorService {

    /**
     * @param doc 文档标题 + 全文 + 结构化切片行（见 {@link DocContentDTO}）
     * @return 抽到的映射列表；文档中不存在的字段**不应**出现在结果里（宁缺勿编）
     */
    List<FieldMappingEntity> extract(DocContentDTO doc);

    /** 实现标识（实际模型名），进留痕日志与前端展示 */
    String name();
}
