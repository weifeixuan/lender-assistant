package com.lender.assistant.rag.vo.request;

/**
 * POST /api/qa 请求体（原 Map&lt;String,Object&gt; → record，§12）。
 * 组件名 question / topK 与旧 JSON 键名逐字一致；topK 用包装类型以便区分"未传"与"非法"。
 */
public record QaRequest(String question, Integer topK) {
}
