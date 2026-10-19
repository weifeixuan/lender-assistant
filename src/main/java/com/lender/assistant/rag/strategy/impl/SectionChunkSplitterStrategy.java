package com.lender.assistant.rag.strategy.impl;

import com.lender.assistant.rag.model.Chunk;
import com.lender.assistant.rag.model.ParsedDocument;
import com.lender.assistant.rag.strategy.ChunkSplitterStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 策略 B：按标题层级切分（结构感知）。
 *
 * 规则：一个 chunk = 一个"叶子小节"（相邻两个标题之间的所有内容），
 * 超长的小节内部再按 chunk-size 二次硬切（同样留 overlap）。
 *
 * 为什么对资方文档天然合适：接口文档是高度结构化的——
 * "3 日末文件 > 3.2 逾期文件格式 > 字段说明"这样的标题树就是语义边界，
 * 按它切几乎不会把一张字段表切成两半。
 *
 * 代价：块大小不均匀（有的小节 30 字，有的 800 字）。
 * 面试故事线：阶段 3 用评估集对比 fixed vs section 的召回率，
 * 这两个类就是对比实验的载体。
 */
@Component
@ConditionalOnProperty(name = "rag.split.strategy", havingValue = "section")
public class SectionChunkSplitterStrategy implements ChunkSplitterStrategy {

    private final int chunkSize;
    private final int overlap;

    public SectionChunkSplitterStrategy(@Value("${rag.split.chunk-size}") int chunkSize,
                                        @Value("${rag.split.overlap}") int overlap) {
        // 与 FixedSizeChunkSplitterStrategy 同一条约束：二次切分步长 = chunkSize - overlap，
        // overlap >= chunkSize 时步长 <= 0，flush() 的循环将无法前进（死循环/OOM）。
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须小于 chunk-size，否则二次切分永远无法前进");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> split(ParsedDocument doc) {
        List<Chunk> chunks = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();   // 从根到当前的标题栈
        StringBuilder buffer = new StringBuilder();        // 当前小节正文

        for (ParsedDocument.Block block : doc.blocks()) {
            if (block.type() == ParsedDocument.BlockType.HEADING) {
                // 遇到新标题：把上一个小节落盘，再调整标题栈
                flush(chunks, headingStack, buffer);
                while (headingStack.size() >= block.level()) {
                    headingStack.pollLast();   // 回到同级或上级标题
                }
                headingStack.addLast(block.text());
            } else {
                if (!buffer.isEmpty()) {
                    buffer.append('\n');
                }
                buffer.append(block.text());
            }
        }
        flush(chunks, headingStack, buffer);   // 最后一个小节
        return chunks;
    }

    private void flush(List<Chunk> chunks, Deque<String> headingStack, StringBuilder buffer) {
        String content = buffer.toString().trim();
        buffer.setLength(0);
        if (content.isEmpty()) {
            return;
        }
        String sectionPath = String.join(" > ", headingStack);
        if (content.length() <= chunkSize) {
            chunks.add(new Chunk(sectionPath, chunks.size(), content, content.length()));
        } else {
            // 超长小节二次硬切（带重叠），sectionPath 不变
            for (int start = 0; start < content.length(); start += chunkSize - overlap) {
                int end = Math.min(start + chunkSize, content.length());
                String part = content.substring(start, end).trim();
                if (!part.isEmpty()) {
                    chunks.add(new Chunk(sectionPath, chunks.size(), part, part.length()));
                }
                if (end == content.length()) {
                    break;
                }
            }
        }
    }
}
