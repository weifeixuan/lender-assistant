package com.lender.assistant.rag.model.response;

/**
 * 文档列表项响应：字段与 {@code DocSummaryDTO} 同构，但接口层只暴露 response 模型，
 * 避免服务端 DTO 的后续改动直接泄漏成对外契约。
 */
public record DocSummaryResponse(String docId, String docTitle, String sourcePath,
                                 Integer chunkCount, Long charCountTotal, String updateTime) {
}
