package com.lender.assistant.rag.model;

import java.util.List;

/**
 * 解析结果：带结构信息的全文。
 *
 * blocks 保留"块类型 + 文本"，是切分策略的输入：
 * - heading 块携带层级，SectionChunkSplitterStrategy 靠它按章节切分；
 * - paragraph 块是普通正文；
 * - tableText 是表格的行文本（POI 把 docx 表格逐行转成"列1: xx | 列2: yy"格式，
 *   保留列名到值的映射信息——资方文档里的字段表是 RAG 问答的核心内容）。
 *
 * 为什么不用纯 String：纯文本丢掉标题层级后，"按标题段落"切分就没法做了，
 * 引用溯源也定位不到章节。
 */
public record ParsedDocument(String fileName, List<Block> blocks) {

    public enum BlockType { HEADING, PARAGRAPH, TABLE_TEXT }

    public record Block(BlockType type, int level, String text) {
        public static Block heading(int level, String text) {
            return new Block(BlockType.HEADING, level, text);
        }

        public static Block paragraph(String text) {
            return new Block(BlockType.PARAGRAPH, 0, text);
        }

        public static Block tableText(String text) {
            return new Block(BlockType.TABLE_TEXT, 0, text);
        }
    }
}
