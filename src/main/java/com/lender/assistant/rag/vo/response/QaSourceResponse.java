package com.lender.assistant.rag.vo.response;

/**
 * SSE "sources" 事件载荷的元素：前端按 idx 展示「来源 N」，并用 docTitle / sectionPath 渲染出处；
 * chunkId / content / 双通道命中与 RRF 分数供前端"检索过程"侧栏展示。
 *
 * 字段全部来自本次召回结果（SearchItemResponse），不额外查库；缺失值（如纯关键词召回无
 * vectorDistance）保持 null，前端自行判空展示。
 */
public record QaSourceResponse(Integer idx, String docTitle, String sectionPath,
                               Long chunkId, String content, Double vectorDistance,
                               Integer keywordHits, Boolean bothChannels, String rrfScore) {
}
