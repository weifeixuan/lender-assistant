package com.lender.assistant.rag.vo.response;

/**
 * SSE "sources" 事件载荷的元素：前端按 idx 展示「来源 N」、用 docTitle / sectionPath 渲染出处。
 * 键名 idx / docTitle / sectionPath 不变（SSE 事件名由 QaServiceImpl 固定，本类只作元素载体）。
 */
public record QaSourceResponse(int idx, String docTitle, String sectionPath) {
}
