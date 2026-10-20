package com.lender.assistant.draft.vo.response;

/**
 * 代码草稿生成结果（对应 POST /api/draft/{docId} 的响应体）。
 * 组件名 docId / provider / reviewNotice / mappingsUsed / draft 与旧契约逐字一致（前端按字段名渲染）；
 * reviewNotice 恒非空——"AI 代码必须人工复核"红线在契约层固化。
 */
public record DraftResponse(String docId, String provider, String reviewNotice,
                            int mappingsUsed, String draft) {
}
