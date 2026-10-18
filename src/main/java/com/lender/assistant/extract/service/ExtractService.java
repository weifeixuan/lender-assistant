package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.FieldMapping;

import java.util.List;

/**
 * 结构化抽取编排：加载已入库文档全文 → 经韧性执行器调抽取器。
 * 抽接口的理由：抽取是"非流式一锤子调用 + 允许重试"的固定契约，
 * 上层（Controller）只关心出参，不关心走的是 Mock 还是 DeepSeek。
 */
public interface ExtractService {

    /** @throws IllegalArgumentException 文档不存在或未入库 */
    List<FieldMapping> extractFromDoc(String docId);
}
