package com.lender.assistant.draft.service;

import com.lender.assistant.draft.vo.response.DraftResponse;

/**
 * 代码草稿生成编排：加载映射表 → 经韧性执行器调生成器 → 附人工复核声明。
 * 抽接口的理由：{@link DraftResponse} 是 API 契约（前端按字段名渲染），
 * 复核声明永不省略，这两点由接口固定下来。
 */
public interface DraftService {

    /** 生成结果；reviewNotice 恒非空——"AI 代码必须人工复核"红线在契约层固化 */
    DraftResponse generate(String docId);
}
