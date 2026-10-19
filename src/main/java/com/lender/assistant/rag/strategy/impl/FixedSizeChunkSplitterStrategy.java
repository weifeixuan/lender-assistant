package com.lender.assistant.rag.strategy.impl;

import com.lender.assistant.rag.model.Chunk;
import com.lender.assistant.rag.model.ParsedDocument;
import com.lender.assistant.rag.strategy.ChunkSplitterStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略 A：固定字数滑动窗口切分。
 *
 * 优点：实现简单、块大小均匀，向量长度分布稳定；
 * 缺点：完全无视语义边界，一个字段表可能被拦腰斩断。
 *
 * overlap（重叠区）的作用：设 chunk-size=500、overlap=100，
 * 第 2 块从第 1 块的第 400 字符开始——如果某个语义单元恰好被 400 处截断，
 * 第 2 块的开头会补全它的尾巴，不至于两边都检索不到完整语义。
 * 重叠是精度换覆盖率，太大（超过 chunk-size 一半）会造成大量冗余存储。
 */
@Component
@ConditionalOnProperty(name = "rag.split.strategy", havingValue = "fixed")
public class FixedSizeChunkSplitterStrategy implements ChunkSplitterStrategy {

    private final int chunkSize;
    private final int overlap;

    public FixedSizeChunkSplitterStrategy(@Value("${rag.split.chunk-size}") int chunkSize,
                                          @Value("${rag.split.overlap}") int overlap) {
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须小于 chunk-size，否则永远无法前进");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> split(ParsedDocument doc) {
        // 先把全文拼起来：标题行也拼进去（标题本身是强检索信号），
        // 记录每个 block 起始位置，切完后回查位置所属 block 推导章节路径
        StringBuilder full = new StringBuilder();
        List<int[]> blockSpans = new ArrayList<>();   // [start, end) per block
        List<String> blockTexts = new ArrayList<>();
        for (ParsedDocument.Block block : doc.blocks()) {
            if (!full.isEmpty()) {
                full.append('\n');
            }
            int start = full.length();
            String text = block.type() == ParsedDocument.BlockType.HEADING
                    ? "## " + block.text()   // 标题加标记，切分后仍有"这是标题"的信息
                    : block.text();
            full.append(text);
            blockSpans.add(new int[]{start, full.length()});
            blockTexts.add(text);
        }

        List<Chunk> chunks = new ArrayList<>();
        String text = full.toString();
        int index = 0;
        for (int start = 0; start < text.length(); start += chunkSize - overlap) {
            int end = Math.min(start + chunkSize, text.length());
            String content = text.substring(start, end).trim();
            if (content.isEmpty()) {
                continue;
            }
            chunks.add(new Chunk(
                    sectionPathAt(blockSpans, blockTexts, start),
                    index++, content, content.length()));
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }

    /** start 落在哪个 block 内，就用"从文档头到该 block 的标题串"作章节路径 */
    private String sectionPathAt(List<int[]> spans, List<String> texts, int pos) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i)[0] > pos) {
                break;
            }
            // 只要把标题 block（含 pos 所在 block 之前的）串起来
            if (texts.get(i).startsWith("## ")) {
                if (!path.isEmpty()) {
                    path.append(" > ");
                }
                path.append(texts.get(i).substring(3));
            }
        }
        return path.toString();
    }
}
