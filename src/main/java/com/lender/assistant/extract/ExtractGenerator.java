package com.lender.assistant.extract;

import java.util.List;

/**
 * 结构化抽取的端口：新资方文档文本进 → 内部字段映射表出。
 *
 * 与 AnswerGenerator 同一套端口模式：
 * - mock    -> MockExtractGenerator（规则+正则，无 key 开发联调用，默认）
 * - deepseek-> DeepSeekExtractGenerator（真实模型，JSON Schema 抽取，key 到位后切换）
 * 装配由 rag.extract.provider 配置决定。
 */
public interface ExtractGenerator {

    /**
     * @param docTitle 文档标题（lender_name 通常在这里）
     * @param docText  文档全文（各 chunk 拼接后的纯文本）
     * @return 抽到的映射列表；文档中不存在的字段**不应**出现在结果里（宁缺勿编）
     */
    List<FieldMapping> extract(String docTitle, String docText);

    /** 实现标识（"Mock" / "DeepSeek"），进留痕日志与前端展示 */
    String name();
}
