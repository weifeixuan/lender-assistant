package com.lender.assistant.rag.splitter;

import com.lender.assistant.rag.parser.ParsedDocument;

import java.util.List;

/**
 * 切分策略抽象。策略模式：两种实现（固定字数 / 按标题段落），
 * 阶段 3 用同一批文档 + 评估集对比两种策略的召回率，配置切换。
 */
public interface ChunkSplitter {

    List<Chunk> split(ParsedDocument doc);
}
