package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.model.FieldMappingRow;

import java.util.List;

/**
 * 结构化抽取编排 + field_mapping 领域服务。
 *
 * 为什么把"映射表读写"也收到这里：field_mapping 是 extract 领域的私有数据，
 * Controller 与 draft 领域（草稿生成）都只经本接口访问它（§1：跨领域只允许 service → service），
 * 从而 DocumentStore / FieldMappingStore 这类直连数据层的类从调用方视野里彻底消失。
 */
public interface ExtractService {

    /** @throws IllegalArgumentException 文档不存在或未入库 */
    List<FieldMapping> extractFromDoc(String docId);

    /** 落库：先清该文档 stale PENDING 再批量 upsert（只覆盖 PENDING 行），返回写入条数 */
    int saveMappings(String docId, List<FieldMapping> mappings);

    /** 某文档完整映射行（含状态），供 GET /api/extract/{docId} */
    List<FieldMappingRow> listMappings(String docId);

    /** 类型化查询（内部字段名由字段目录补全），供草稿生成 */
    List<FieldMapping> listFieldMappings(String docId);

    /** 人工确认/修正：correctedValue 非空 → CORRECTED，否则 → CONFIRMED */
    void confirm(String docId, String internalFieldId, String correctedFieldName, String correctedValue);
}
