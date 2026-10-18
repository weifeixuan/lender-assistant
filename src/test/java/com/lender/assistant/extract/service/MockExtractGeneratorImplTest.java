package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.service.impl.MockExtractGeneratorImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock 抽取器的特征测试：fixture 是瑞金租赁文档风格的脱敏文本，
 * 期望值来自 docs/plan_phase4.md 第二节"文档中的出处"表。
 *
 * Mock 抽取的意义（和 HashEmbeddingClient 同一个道理）：
 * 不验证"抽取效果好不好"（那是 DeepSeek 版的事），
 * 验证"抽取链路和输出结构对不对"——字段落位、置信度、缺失字段的处理。
 */
class MockExtractGeneratorImplTest {

    private final MockExtractGeneratorImpl generator = new MockExtractGeneratorImpl();

    /** 瑞金租赁（虚构）文档的正文片段 + 标题，字段拼写与 gen_sample_docs.py 保持一致 */
    private static final String RUIJIN_TEXT = """
            瑞金租赁 资金对接接口文档
            一、接口总则
            瑞金租赁（虚构）资金对接采用 REST 风格，网关地址 https://fake-ruijin.example.com/openapi。\
            与华信银行不同，本家资方要求所有请求放置于 JSON 包体中，使用 RSA2 私钥签名，\
            签名放置于请求体字段 sign 中而非请求头。
            （一）商户准入
            合作机构接入前需完成商户备案，取得 partner_id 与 partner_key。\
            partner_key 仅用于本地生成签名，不得随报文传输。
            二、放款指令接口
            接口路径 /openapi/loan/order/create。用于合作机构向瑞金租赁发起单笔放款指令。\
            指令受理成功返回受理编号，放款结果异步通知。
            异步通知字段：notify_type 取值 LOAN_RESULT；loan_status 取值 SUCCESS / FAILED / PROCESSING；\
            fail_reason 在失败时返回中文描述。
            三、对账单文件
            瑞金租赁不提供日末 SFTP 文件，改为 T+1 日上午 10 点前开放对账单下载接口 /openapi/reconcile/download，\
            参数 biz_date 指定对账日期，返回 CSV 内容，UTF-8 编码，逗号分隔。
            """;

    private Map<String, FieldMapping> extractAsMap(String title, String text) {
        List<FieldMapping> mappings = generator.extract(title, text);
        return mappings.stream()
                .collect(Collectors.toMap(FieldMapping::internalFieldId, m -> m));
    }

    @Test
    @DisplayName("lender_name：从文档标题提取资方名称")
    void extractsLenderName() {
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", RUIJIN_TEXT);
        assertEquals("瑞金租赁", m.get("lender_name").lenderValue());
    }

    @Test
    @DisplayName("gateway_url / uat_url：精确 URL 抽取，瑞金无联调地址则不产出该字段")
    void extractsGatewayUrls() {
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", RUIJIN_TEXT);
        assertEquals("https://fake-ruijin.example.com/openapi", m.get("gateway_url").lenderValue());
        assertFalse(m.containsKey("uat_url"), "瑞金文档没写联调环境地址——缺失字段不应编造");
    }

    @Test
    @DisplayName("auth_method / credentials：鉴权与准入凭证")
    void extractsAuthAndCredentials() {
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", RUIJIN_TEXT);
        assertTrue(m.get("auth_method").lenderValue().contains("RSA2"));
        assertTrue(m.get("credentials").lenderValue().contains("partner_id"));
        assertTrue(m.get("credentials").lenderValue().contains("partner_key"));
    }

    @Test
    @DisplayName("payment_api：放款指令接口路径；collection_api 瑞金没有则缺失")
    void extractsApiPaths() {
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", RUIJIN_TEXT);
        assertEquals("/openapi/loan/order/create", m.get("payment_api").lenderValue());
        assertFalse(m.containsKey("collection_api"), "瑞金无代收接口——缺失不应编造");
    }

    @Test
    @DisplayName("status_enum / file_delivery / file_format：状态枚举与对账文件要素")
    void extractsStatusAndFileFields() {
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", RUIJIN_TEXT);
        assertTrue(m.get("status_enum").lenderValue().contains("SUCCESS"));
        assertTrue(m.get("status_enum").lenderValue().contains("PROCESSING"));
        assertTrue(m.get("file_delivery").lenderValue().contains("T+1"));
        assertTrue(m.get("file_format").lenderValue().contains("UTF-8"));
    }

    @Test
    @DisplayName("每条映射带原文出处与 (0,1] 置信度，且 internalFieldName 来自字段目录")
    void mappingsCarrySourceQuoteAndConfidence() {
        List<FieldMapping> mappings = generator.extract("瑞金租赁_资金对接接口文档_v1.0", RUIJIN_TEXT);
        assertFalse(mappings.isEmpty());
        for (FieldMapping fm : mappings) {
            assertNotNull(InternalFieldCatalog.byId(fm.internalFieldId()), "未知字段 id: " + fm.internalFieldId());
            assertEquals(InternalFieldCatalog.byId(fm.internalFieldId()).name(), fm.internalFieldName());
            assertFalse(fm.sourceQuote() == null || fm.sourceQuote().isBlank(),
                    fm.internalFieldId() + " 缺原文出处——引用溯源是红线级要求");
            assertTrue(fm.confidence() > 0 && fm.confidence() <= 1.0,
                    fm.internalFieldId() + " 置信度越界: " + fm.confidence());
        }
    }

    @Test
    @DisplayName("回归：章节标题在 section_path 前缀行（content 里没有标题），路径仍能按章节分类")
    void apiPathClassifiedWhenHeadingNotAtLineStart() {
        // 真实数据形态：SectionSplitter 把标题存进 section_path 而非 content，
        // loadDocContent 拼接后每个 chunk 以【章节】行开头
        String crossChunk = """
                【章节】瑞金租赁 资金对接接口文档 > 一、接口总则
                瑞金租赁（虚构）资金对接采用 REST 风格，网关地址 https://fake-ruijin.example.com/openapi。
                【章节】瑞金租赁 资金对接接口文档 > 二、放款指令接口
                接口路径 /openapi/loan/order/create。用于合作机构向瑞金租赁发起单笔放款指令。
                """;
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", crossChunk);
        assertTrue(m.containsKey("payment_api"), "章节信息来自【章节】前缀行，路径必须归入 payment_api");
        assertEquals("/openapi/loan/order/create", m.get("payment_api").lenderValue());
    }

    @Test
    @DisplayName("回归：状态枚举取最长匹配，不被硬切分的截断行骗到")
    void statusEnumPrefersLongestMatch() {
        // 真实场景：chunk2 行尾被切成 "SUCCESS / FAIL"，完整枚举在 chunk3
        String truncated = """
                异步通知字段：notify_type 取值 LOAN_RESULT；loan_status 取值 SUCCESS / FAIL
                ring(200) | 是否必填: 是 | 备注: 资金用途
                异步通知字段：loan_status 取值 SUCCESS / FAILED / PROCESSING；fail_reason 在失败时返回中文描述。
                """;
        Map<String, FieldMapping> m = extractAsMap("瑞金租赁_资金对接接口文档_v1.0", truncated);
        assertTrue(m.get("status_enum").lenderValue().contains("PROCESSING"),
                "应取最完整的枚举而非第一个（截断的）匹配");
    }

    @Test
    @DisplayName("回归：接口文档里的'报文编码/对账文件提及'不触发对账文件字段（防误报）")
    void apiDocDoesNotFalsePositiveFileFields() {
        // 真实华信 API 文档的陷阱句：提及"对账文件"但不是文件获取方式；"UTF-8 编码"指报文非对账文件
        String trap = """
                华信银行 对公收付接口文档
                本接口用于合作机构通过 HTTP 通道发起对公账户的代收与代付交易。\
                生产环境网关地址为 https://fake-huaxin.example.com/gateway。请求报文统一使用 UTF-8 编码的 JSON。
                响应中的核心字段：hxSeqNo 为我行流水号，最终态以对账文件或交易查询接口为准，状态为 PROCESSING 时不得直接作为失败处理。
                """;
        Map<String, FieldMapping> m = extractAsMap("华信银行_对公收付接口文档_v1.0", trap);
        assertFalse(m.containsKey("file_delivery"), "'以对账文件…为准'不是文件获取方式，不得误报");
        assertFalse(m.containsKey("file_format"), "'报文 UTF-8 编码'不是对账文件格式，不得误报");
        assertEquals("https://fake-huaxin.example.com/gateway", m.get("gateway_url").lenderValue());
    }

    @Test
    @DisplayName("华信文档（含代收/查询接口与联调地址）对照：字段差异化产出")
    void huaxinDocProducesDifferentFields() {
        String huaxin = """
                华信银行 对公收付接口文档
                1 接入约定
                生产环境网关地址为 https://fake-huaxin.example.com/gateway，联调环境为 https://fake-huaxin-uat.example.com/gateway。
                所有接口采用 HTTPS 双向证书认证。合作机构需向我行提供机构证书公钥，我行分配合作机构编号（merNo）与接入密钥（secretKey）。
                2 单笔代收接口
                代收指从我行企业客户的对公账户扣划资金至合作机构归集账户。接口路径 /v1/collections/single。
                4 交易查询接口
                接口路径 /v1/transactions/query。支持按 outBizNo 或 hxSeqNo 查询。
                响应中的核心字段：hxSeqNo 为我行流水号，status 取值 PROCESSING/SUCCESS/FAIL。
                """;
        Map<String, FieldMapping> m = extractAsMap("华信银行_对公收付接口文档_v1.0", huaxin);
        assertEquals("华信银行", m.get("lender_name").lenderValue());
        assertEquals("https://fake-huaxin.example.com/gateway", m.get("gateway_url").lenderValue());
        assertEquals("https://fake-huaxin-uat.example.com/gateway", m.get("uat_url").lenderValue());
        assertEquals("/v1/collections/single", m.get("collection_api").lenderValue());
        assertEquals("/v1/transactions/query", m.get("query_api").lenderValue());
        assertEquals("HTTPS 双向证书认证", m.get("auth_method").lenderValue());
        assertFalse(m.containsKey("payment_api"), "华信 fixture 没写代付路径（真实文档在 3 节），缺失不编造");
    }
}
