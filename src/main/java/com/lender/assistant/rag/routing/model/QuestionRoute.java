package com.lender.assistant.rag.routing.model;

/**
 * 问题路由的分类结果。
 *
 * 注意：本枚举值 {@code PLAN} 与 SSE 事件名 {@code plan} 不是一回事——
 * 前者是分类结果，后者是计划事件，命名不得互相替代。
 */
public enum QuestionRoute {

    /** 不需要也不应使用知识库，且与资方接入无关 */
    IRRELEVANT,

    /** 依据知识库回答，单次检索 + 单次生成可覆盖 */
    KNOWLEDGE,

    /** 需要多个信息点或跨文档比较/汇总 */
    PLAN;

    /** 大小写不敏感解析；无法识别返回 null，由调用方降级 */
    public static QuestionRoute from(String value) {
        if (value == null) {
            return null;
        }
        for (QuestionRoute route : values()) {
            if (route.name().equalsIgnoreCase(value.trim())) {
                return route;
            }
        }
        return null;
    }
}
