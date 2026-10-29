package com.lender.assistant.rag.vo.response;

/**
 * 文档切片条目（前端"查看切分信息"抽屉的一行）：序号、章节路径、字符数与原文。
 * content 传原文（单条切片量级小），前端自行做截断与展开。
 */
public record ChunkItemResponse(Long chunkId, Integer index, String sectionPath,
                                Integer charCount, String content) {
}
