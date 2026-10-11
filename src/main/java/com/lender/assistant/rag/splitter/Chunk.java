package com.lender.assistant.rag.splitter;

import com.lender.assistant.rag.parser.ParsedDocument;

import java.util.List;

/**
 * 切分结果：一个 chunk 就是要入库并向量化的最小单元。
 * sectionPath 是"标题面包屑"（如 "3 日末文件 > 3.2 逾期文件格式"），
 * 既是检索上下文，也是引用溯源的展示内容。
 */
public record Chunk(String sectionPath, int index, String content, int charCount) {

    public static List<Chunk> empty() {
        return List.of();
    }
}
