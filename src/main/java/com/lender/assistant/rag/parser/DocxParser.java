package com.lender.assistant.rag.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Word(.docx) 解析器，基于 POI 的 XWPF（处理 .docx 的 OOXML API）。
 *
 * 关键点：XWPFParagraph.getStyleID() 拿到段落的样式名（如 "1"/"2"/"Heading1"），
 * 自制文档用 Word 内置 Heading 1/2/3 样式写标题，这里把样式 ID 映射回层级数字。
 * 如果你的模板样式名不同，改 headingLevelOf() 一处即可。
 *
 * 表格：逐行转成 "列名: 值 | 列名: 值" 文本——接口文档的字段表经这样转换后，
 * "逾期天数在第几列"这类问题的答案会以自然语言形式出现在 chunk 里。
 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) throws Exception {
        List<ParsedDocument.Block> blocks = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            // bodyElements 按文档实际顺序返回段落和表格，不会打乱阅读顺序；
            // PARAGRAPH 元素本身就是 XWPFParagraph 实例，直接强转即可
            for (var element : doc.getBodyElements()) {
                switch (element.getElementType()) {
                    case PARAGRAPH -> {
                        XWPFParagraph p = (XWPFParagraph) element;
                        String text = p.getText().trim();
                        if (text.isEmpty()) {
                            continue;
                        }
                        int level = headingLevelOf(p);
                        if (level > 0) {
                            blocks.add(ParsedDocument.Block.heading(level, text));
                        } else {
                            blocks.add(ParsedDocument.Block.paragraph(text));
                        }
                    }
                    case TABLE -> {
                        XWPFTable table = (XWPFTable) element;
                        blocks.add(ParsedDocument.Block.tableText(renderTable(table)));
                    }
                    default -> { /* 忽略其他元素类型 */ }
                }
            }
        }
        return new ParsedDocument(fileName, blocks);
    }

    /** 表格第一行当表头，之后每行渲染成 "表头: 值 | 表头: 值" */
    private String renderTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }
        List<String> headers = rows.get(0).getTableCells().stream()
                .map(c -> c.getText().trim())
                .toList();
        StringBuilder sb = new StringBuilder();
        for (int r = 1; r < rows.size(); r++) {
            StringBuilder line = new StringBuilder();
            var cells = rows.get(r).getTableCells();
            for (int c = 0; c < cells.size() && c < headers.size(); c++) {
                if (c > 0) {
                    line.append(" | ");
                }
                line.append(headers.get(c)).append(": ").append(cells.get(c).getText().trim());
            }
            if (!line.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** 把 Word 标题样式映射为层级数字，非标题样式返回 0 */
    private int headingLevelOf(XWPFParagraph p) {
        String style = p.getStyleID();
        if (style == null) {
            return 0;
        }
        // Word 中文版样式 ID 常见为 "1"/"2"/"3"，英文版为 "Heading1"...，都兼容
        String s = style.toLowerCase().replace("heading", "");
        try {
            int level = Integer.parseInt(s.trim());
            return level >= 1 && level <= 6 ? level : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
