package com.lender.assistant.rag.strategy;

import com.lender.assistant.rag.model.ParsedDocument;

/**
 * 文档解析策略：文件字节流 -> 带结构信息的 ParsedDocument。
 *
 * 与"provider 二选一"的策略不同，解析是**按扩展名运行时分发**——同一时刻多个实现
 * 都在容器里（TextDocumentParserStrategy / DocxDocumentParserStrategy），
 * 由 {@link com.lender.assistant.rag.strategy.factory.DocumentParserStrategyFactory}
 * 统一选择，调用方不写 if/else。后续加 PDF 只需新增实现类 + supports 规则，调用方无感。
 */
public interface DocumentParserStrategy {

    /** 是否支持该文件名（按扩展名判断） */
    boolean supports(String fileName);

    ParsedDocument parse(String fileName, byte[] content) throws Exception;
}
