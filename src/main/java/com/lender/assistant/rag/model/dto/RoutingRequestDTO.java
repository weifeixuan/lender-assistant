package com.lender.assistant.rag.model.dto;

/**
 * 路由输入。
 *
 * @param previousQuestion 上一轮用户问题；仅用于解析指代，不并入本轮目标
 * @param previousRoute    最近一条 DONE 助手消息的 route；无可用历史为 null
 * @param knowledgeCatalog 知识库目录（仅 docId + docTitle），可为空串
 */
public record RoutingRequestDTO(String question, String previousQuestion,
                             QuestionRoute previousRoute, String knowledgeCatalog) {
}
