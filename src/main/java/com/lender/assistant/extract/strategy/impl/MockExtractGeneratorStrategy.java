package com.lender.assistant.extract.strategy.impl;

import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import com.lender.assistant.extract.strategy.ExtractGeneratorStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 结构化抽取：规则 + 正则，零模型依赖（与 HashEmbeddingStrategy 同一策略）。
 *
 * 定位：它只证明"抽取链路与输出结构是对的"，不证明"抽取效果好"——
 * 规则只能覆盖文档的标准表述形态，真实效果等真实模型版（JSON Schema 抽取）。
 *
 * 抽取策略（每类字段一种武器，按可靠性排序）：
 * 1. 精确值正则（置信 0.9）：URL、接口路径、状态枚举——格式固定，正则即可锁定；
 * 2. 语义句定位（置信 0.7）：鉴权/凭证/签名/文件方式——取"含关键词的首个句子"作值，
 *    粗但安全（宁多勿编）；
 * 3. 缺失即缺失：文档没写的字段绝不进结果——测试用瑞金"无代收接口"锁死这条。
 *
 * 章节感知：接口路径按"最近的章节标题"分类（放款/代收/查询），
 * 因为"接口路径 /v1/xxx"本身不带业务语义，语义在标题里。
 */
@Component
@ConditionalOnProperty(name = "rag.extract.provider", havingValue = "mock", matchIfMissing = true)
public class MockExtractGeneratorStrategy implements ExtractGeneratorStrategy {

    private static final double CONF_EXACT = 0.9;
    private static final double CONF_SENTENCE = 0.7;

    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9._/-]+");
    private static final Pattern GATEWAY = Pattern.compile("网关地址(?:为)?\\s*(https?://[A-Za-z0-9._/-]+)");
    private static final Pattern UAT = Pattern.compile("联调环境(?:地址)?(?:为)?\\s*(https?://[A-Za-z0-9._/-]+)");
    private static final Pattern API_PATH = Pattern.compile("接口路径\\s*(/[A-Za-z][\\w/-]+)");
    private static final Pattern STATUS_ENUM = Pattern.compile(
            "status\\s*取值\\s*([A-Z_]+(?:\\s*/\\s*[A-Z_]+)+)");
    private static final Pattern AUTH = Pattern.compile(
            "(HTTPS?\\s*)?(双向证书认证|证书认证|RSA2\\s*私钥签名|RSA2私钥签名)");
    private static final Pattern CHARSET = Pattern.compile("(GBK|GB2312|UTF-?8|Unicode|ASCII)");
    private static final Pattern DELIMITER = Pattern.compile("(竖线|逗号|制表符|分隔符为\\s*\\S{1,3})");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^\\s*(?:\\d+(?:\\.\\d+)*\\s|[一二三四五六七八九十]+[、.]|（[一二三四五六七八九十]+）)");

    @Override
    public List<FieldMapping> extract(String docTitle, String docText) {
        List<FieldMapping> out = new ArrayList<>();
        List<String> lines = List.of(docText.split("\\r?\\n|。"));

        // 1. 资方名称：标题 → 首行兜底
        String lenderName = extractLenderName(docTitle, docText);
        if (lenderName != null) {
            out.add(new FieldMapping("lender_name", "资方名称", "资方自称", lenderName,
                    firstLineOf(docText), CONF_EXACT));
        }

        // 2. URL 类：精确正则
        addRegexMapping(out, docText, "gateway_url", "生产网关地址", "网关地址", GATEWAY, CONF_EXACT);
        addRegexMapping(out, docText, "uat_url", "联调环境地址", "联调环境", UAT, CONF_EXACT);

        // 3. 鉴权方式：关键词句定位
        Matcher authMatcher = AUTH.matcher(docText);
        String auth = authMatcher.find() ? authMatcher.group().trim() : null;
        if (auth != null) {
            out.add(new FieldMapping("auth_method", "鉴权方式", "认证/签名表述", auth,
                    lineContaining(lines, auth), CONF_SENTENCE));
        }

        // 4. 准入凭证：含"备案/取得/分配/密钥"的句子 + 句内标识符
        String credSentence = firstSentenceContaining(docText, "备案", "取得", "分配", "密钥", "secret", "partner");
        if (credSentence != null) {
            List<String> ids = IDENTIFIER.matcher(credSentence).results()
                    .map(mr -> mr.group()).distinct().toList();
            if (!ids.isEmpty()) {
                out.add(new FieldMapping("credentials", "准入凭证字段",
                        String.join(", ", ids), credSentence.trim(),
                        lineContaining(lines, ids.get(0)), CONF_SENTENCE));
            }
        }

        // 5. 签名规则：含"签名"且带算法细节（SHA/sign/私钥/字典序）的句子
        String signSentence = firstSentenceContaining(docText, "字典序", "SHA", "sign", "私钥");
        if (signSentence == null) {
            signSentence = firstSentenceContaining(docText, "签名");
        }
        if (signSentence != null) {
            out.add(new FieldMapping("sign_rule", "签名规则", "签名表述", signSentence.trim(),
                    lineContaining(lines, "签名"), CONF_SENTENCE));
        }

        // 6. 接口路径 ×3：按最近章节标题分类
        out.addAll(extractApiPaths(lines));

        // 7. 状态枚举：收集全部匹配取最长——硬切分可能把枚举截断（"SUCCESS / FAIL"），
        //    完整值在相邻 chunk 里；首匹配会抽到残缺形态，最长匹配最稳
        Matcher status = STATUS_ENUM.matcher(docText);
        String bestStatus = null;
        while (status.find()) {
            if (bestStatus == null || status.group(1).length() > bestStatus.length()) {
                bestStatus = status.group(1).trim();
            }
        }
        if (bestStatus != null) {
            out.add(new FieldMapping("status_enum", "交易状态枚举", "status", bestStatus,
                    lineContaining(lines, bestStatus), CONF_EXACT));
        }

        // 8. 对账文件：获取方式与格式。
        //    规则必须收紧：API 文档里"以对账文件…为准"、"报文 UTF-8 编码"这类提及
        //    不是文件获取/格式描述，宽匹配会误报（有回归测试锁着）
        String delivery = firstSentenceMatching(docText, s -> {
            String low = s.toLowerCase();
            return low.contains("sftp") || low.contains("对账单下载")
                    || (low.contains("对账") && (low.contains("生成") || low.contains("推送") || low.contains("下载")))
                    || (low.contains("日末文件") && (low.contains("生成") || low.contains("推送")));
        });
        if (delivery != null) {
            out.add(new FieldMapping("file_delivery", "对账文件获取方式", "文件获取", delivery.trim(),
                    lineContaining(lines, "对账"), CONF_SENTENCE));
        }
        String fmtSentence = firstSentenceMatching(docText, s -> {
            String low = s.toLowerCase();
            return low.contains("分隔符") || low.contains("csv") || (low.contains("编码") && low.contains("文件"));
        });
        if (fmtSentence != null) {
            Matcher cs = CHARSET.matcher(fmtSentence);
            Matcher dl = DELIMITER.matcher(fmtSentence);
            if (cs.find()) {
                String value = cs.group();
                if (dl.find()) {
                    value += "，" + dl.group().trim();
                }
                out.add(new FieldMapping("file_format", "对账文件格式", "文件格式", value,
                        lineContaining(lines, cs.group()), CONF_SENTENCE));
            }
        }

        return out;
    }

    @Override
    public String name() {
        return "Mock";
    }

    // ---------- 私有辅助 ----------

    private String extractLenderName(String docTitle, String docText) {
        // 标题形如 "瑞金租赁_资金对接接口文档_v1.0" / "华信银行_对公收付接口文档_v1.0"
        Matcher byTitle = Pattern.compile("^(.+?)(?:_|\\s|（)(?:对公收付|资金对接|日末文件)")
                .matcher(docTitle == null ? "" : docTitle);
        if (byTitle.find()) {
            return byTitle.group(1).trim();
        }
        // 兜底：正文首行 "瑞金租赁 资金对接接口文档"
        String firstLine = firstLineOf(docText);
        Matcher byText = Pattern.compile("^(.{2,20}?)\\s*(?:对公收付|资金对接|日末文件)").matcher(firstLine);
        return byText.find() ? byText.group(1).trim() : null;
    }

    private void addRegexMapping(List<FieldMapping> out, String text, String fieldId,
                                 String fieldName, String lenderField, Pattern p, double conf) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            out.add(new FieldMapping(fieldId, fieldName, lenderField, m.group(1).trim(),
                    lineContaining(List.of(text.split("\\r?\\n|。")), m.group(1)), conf));
        }
    }

    /**
     * 接口路径抽取：路径语义在章节标题里，标题来自两个来源——
     * ①【章节】前缀行（loadDocContent 拼入的 section_path，主通道）；
     * ② 行首编号标题（直接抽取纯文本时的兜底）。
     * 取"路径出现前最近一次声明的章节"做分类。
     */
    private List<FieldMapping> extractApiPaths(List<String> lines) {
        List<FieldMapping> out = new ArrayList<>();
        String section = "";
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("【章节】")) {
                String[] segments = t.split(">");
                section = segments[segments.length - 1].trim();
                continue;
            }
            if (SECTION_HEADING.matcher(t).find() && t.length() < 30) {
                section = t;
            }
            Matcher path = API_PATH.matcher(t);
            if (path.find() && !section.isEmpty()) {
                String fieldId = classifyApiSection(section);
                if (fieldId != null && out.stream().noneMatch(fm -> fm.internalFieldId().equals(fieldId))) {
                    String name = InternalFieldCatalog.byId(fieldId).name();
                    out.add(new FieldMapping(fieldId, name, section, path.group(1), t.trim(), CONF_EXACT));
                }
            }
        }
        return out;
    }

    private String classifyApiSection(String sectionTitle) {
        if (sectionTitle.contains("放款") || sectionTitle.contains("代付")) {
            return "payment_api";
        }
        if (sectionTitle.contains("代收") || sectionTitle.contains("扣款")) {
            return "collection_api";
        }
        if (sectionTitle.contains("查询")) {
            return "query_api";
        }
        return null;
    }

    /** 句子切分按 。/换行，返回首个含任一关键词（大小写不敏感）的句子 */
    private String firstSentenceContaining(String text, String... keywords) {
        for (String s : text.split("。|\\r?\\n")) {
            String lower = s.toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw.toLowerCase())) {
                    return s;
                }
            }
        }
        return null;
    }

    /** 谓词版句子定位：误报敏感的规则用组合条件而不是单词命中 */
    private String firstSentenceMatching(String text, java.util.function.Predicate<String> rule) {
        for (String s : text.split("。|\\r?\\n")) {
            if (rule.test(s)) {
                return s;
            }
        }
        return null;
    }

    private String lineContaining(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return line.trim();
            }
        }
        return needle;
    }

    private String firstLineOf(String text) {
        return text.split("\\r?\\n", 2)[0].trim();
    }
}
