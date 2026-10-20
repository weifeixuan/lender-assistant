package com.lender.assistant.extract.vo.response;

/**
 * 人工确认/修正的结果（对应 POST /api/extract/{docId}/confirm 的响应体）。
 * 旧契约是 {"ok": true}，组件名 ok 保持不变。
 */
public record ConfirmResponse(boolean ok) {
}
