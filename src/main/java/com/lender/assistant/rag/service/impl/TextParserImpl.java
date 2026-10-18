package com.lender.assistant.rag.service.impl;

import com.lender.assistant.rag.model.ParsedDocument;
import com.lender.assistant.rag.service.DocumentParserService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本/Markdown 解析器：按行扫描，# 前缀识别为标题（# 的个数 = 层级）。
 * 用于 .txt / .md。规则简单到一眼能看懂，资方自制样例文档用它就能跑通全链路。
 */
@Component
public class TextParserImpl implements DocumentParserService {

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md");
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<ParsedDocument.Block> blocks = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                blocks.add(ParsedDocument.Block.heading(level, trimmed.substring(level).trim()));
            } else {
                blocks.add(ParsedDocument.Block.paragraph(trimmed));
            }
        }
        return new ParsedDocument(fileName, blocks);
    }
}
