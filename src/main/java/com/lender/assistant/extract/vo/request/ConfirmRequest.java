package com.lender.assistant.extract.vo.request;

/**
 * POST /api/extract/{docId}/confirm 请求体：人工确认/修正一条映射。
 * 组件名 internalFieldId / lenderFieldName / lenderValue 与旧 JSON 键名逐字一致。
 */
public record ConfirmRequest(String internalFieldId, String lenderFieldName, String lenderValue) {
}
