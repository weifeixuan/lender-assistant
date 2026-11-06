package com.lender.assistant.extract.strategy;

import com.lender.assistant.extract.model.entity.FieldMappingEntity;
import com.lender.assistant.rag.model.DocContent;

import java.util.List;

/**
 * 结构化抽取的策略接口：新资方文档内容进 → 内部字段映射表出。
 *
 * 与 AnswerGeneratorStrategy 同一套端口模式：
 * - mock    -> MockExtractGeneratorStrategy（规则+正则，无 key 开发联调用，默认）
 * - openai  -> OpenAiCompatibleExtractGeneratorStrategy（真实模型，OpenAI 兼容协议，JSON Schema 抽取）
 * 装配由 rag.extract.provider 配置决定（构建期二选一，不需要工厂）。
 *
 * 入参是结构化文档内容：真实模型只用 text 拼 Prompt，规则实现还能用 lines 按章节分类。
 */
public interface ExtractGeneratorStrategy {

    /**
     * @param doc 文档标题 + 全文 + 结构化 chunk 行（见 {@link DocContent}）
     * @return 抽到的映射列表；文档中不存在的字段**不应**出现在结果里（宁缺勿编）
     */
    List<FieldMappingEntity> extract(DocContent doc);

    /** 实现标识（"Mock" / 实际模型名），进留痕日志与前端展示 */
    String name();
}
