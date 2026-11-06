package com.lender.assistant.rag.model.request;

/**
 * POST /api/qa 请求体（原 Map&lt;String,Object&gt; → record，§12）。
 * 组件名 question / topK / conversationId 与 JSON 键名逐字一致；
 * conversationId 缺省或 null 表示首问懒创建，topK 用包装类型以便区分"未传"与"非法"。
 */
public record QaRequest(String conversationId, String question, Integer topK) {
}
