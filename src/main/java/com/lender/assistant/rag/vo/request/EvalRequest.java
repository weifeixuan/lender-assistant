package com.lender.assistant.rag.vo.request;

/**
 * POST /api/admin/eval/run 请求体（原 Map → record，§12）。
 * 组件名 mode / topK 与旧 JSON 键名逐字一致；mode 的合法取值见 SearchMode，由 SearchMode.fromCode 统一解析。
 */
public record EvalRequest(String mode, Integer topK) {
}
