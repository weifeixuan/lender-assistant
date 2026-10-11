package com.lender.assistant.rag.parser;

/**
 * 文档解析器抽象：文件字节流 -> 带结构信息的 ParsedDocument。
 *
 * 按扩展名路由到具体实现（TextParser / DocxParser），
 * 后续加 PDF 只需新增实现类，调用方无感。
 */
public interface DocumentParser {

    /** 是否支持该文件名（按扩展名判断） */
    boolean supports(String fileName);

    ParsedDocument parse(String fileName, byte[] content) throws Exception;
}
