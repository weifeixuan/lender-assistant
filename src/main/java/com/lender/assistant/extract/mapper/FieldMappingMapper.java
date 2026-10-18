package com.lender.assistant.extract.mapper;

import com.lender.assistant.extract.model.FieldMappingRaw;
import com.lender.assistant.extract.model.FieldMappingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * field_mapping 表的唯一读写落点（§6.1）。
 *
 * upsert 的"只覆盖 PENDING 行"与"清理只限 PENDING"两条守门落在 XML（§6.8/§6.11）；
 * 清 stale + upsert 的原子性由 ExtractServiceImpl.saveMappings 的 @Transactional 保证。
 * confirm 的两分支（CORRECTED / CONFIRMED）在 Service 判断，此处拆成两条 SQL。
 */
@Mapper
public interface FieldMappingMapper {

    int deleteStalePending(@Param("docId") String docId);

    /** 单条多行 upsert（一次往返）；调用方保证 raws 非空 */
    int upsertAll(@Param("raws") List<FieldMappingRaw> raws);

    List<FieldMappingRow> listRows(@Param("docId") String docId);

    List<FieldMappingRaw> listForDraft(@Param("docId") String docId);

    void confirmCorrected(@Param("docId") String docId, @Param("internalFieldId") String internalFieldId,
                          @Param("correctedFieldName") String correctedFieldName,
                          @Param("correctedValue") String correctedValue);

    void confirmAsIs(@Param("docId") String docId, @Param("internalFieldId") String internalFieldId);
}
