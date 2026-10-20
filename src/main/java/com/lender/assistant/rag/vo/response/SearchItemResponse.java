package com.lender.assistant.rag.vo.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 检索结果条目（对外 JSON 契约载体）：向量通道 / 关键词通道 / hybrid RRF 融合三种模式共用一种结构。
 *
 * 为什么用可空包装类型：不同模式下参与的字段不同——纯向量召回没有 keywordHits、
 * 纯关键词召回没有 vectorDistance、非 hybrid 模式没有 rrfScore。
 * 组件名与旧 Map 键名逐字一致（前端 index.html 按名取值），null 表示"该模式未产出此字段"。
 *
 * 为什么加 NON_NULL：上一版是 LinkedHashMap，只在产出该字段时才 put —— 即**缺字段时 JSON 里没有这个键**。
 * record 默认会把 null 也序列化成 `"keywordHits": null`，属对外契约变化；
 * NON_NULL 让缺失字段不输出，与旧 Map 行为逐字对齐（前端 index.html 虽已用 `??` 容错，但契约不应对不上）。
 *
 * 为什么 sectionPath 单独标 ALWAYS：旧 Map 里它是**无条件 put** 的，即无小节时也输出 `"sectionPath": null`
 * （保留 null 键是旧契约的一部分），故这一项要豁免 NON_NULL。
 *
 * 数值组件用包装类型（Double / Integer）：需要表达"无此值"的 null 语义；
 * 与 §6.6 的 MyBatis <constructor> 场景无关（本类不经 Mapper 映射）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchItemResponse(
        long chunkId,
        String docId,
        String docTitle,
        @JsonInclude(JsonInclude.Include.ALWAYS) String sectionPath,
        String content,
        Double vectorDistance,
        Integer keywordHits,
        boolean bothChannels,
        String rrfScore) {
}
