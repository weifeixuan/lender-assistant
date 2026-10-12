package com.lender.assistant.extract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部标准字段目录（12 个）——抽取映射表的"对照物"，字段清单与出处表
 * 见 docs/plan_phase4.md 第二节（从华信/瑞金两份虚构文档反推）。
 *
 * 为什么写死在代码里而不是配置文件：
 * 这是评估口径的一部分（准确率 = 命中这 12 个字段的比例），
 * 放配置里会被随手改动而无人察觉；代码常量 + 守卫测试把变更显式化。
 * 阶段 5 做代码草稿生成时，这 12 个字段就是适配层模板的入参清单。
 */
public final class InternalFieldCatalog {

    public static final List<InternalField> ALL = List.of(
            new InternalField("lender_name", "资方名称", "资方机构的全称，来自文档标题或正文自称"),
            new InternalField("gateway_url", "生产网关地址", "生产环境 API 网关基础地址"),
            new InternalField("uat_url", "联调环境地址", "联调/UAT 环境 API 网关基础地址"),
            new InternalField("auth_method", "鉴权方式", "接口鉴权机制：证书认证 / 签名算法 / Token 等"),
            new InternalField("credentials", "准入凭证字段", "接入前需获取的凭证参数（机构编号、密钥等）"),
            new InternalField("sign_rule", "签名规则", "报文签名的算法与拼装规则"),
            new InternalField("payment_api", "付款/放款指令接口", "向外付款（放款/代付）的接口路径"),
            new InternalField("collection_api", "收款/代收接口", "向客户扣款（代收）的接口路径"),
            new InternalField("query_api", "交易查询接口", "按流水号查询交易状态的接口路径"),
            new InternalField("status_enum", "交易状态枚举", "交易/指令状态字段的取值集合"),
            new InternalField("file_delivery", "对账文件获取方式", "对账/日末文件的获取渠道与生成时点"),
            new InternalField("file_format", "对账文件格式", "对账文件的编码与分隔符等格式要素")
    );

    private static final Map<String, InternalField> INDEX = new LinkedHashMap<>();

    static {
        for (InternalField f : ALL) {
            INDEX.put(f.id(), f);
        }
    }

    private InternalFieldCatalog() {
    }

    /** 按 id 取字段定义，未知 id 返回 null（由调用方决定如何处理） */
    public static InternalField byId(String id) {
        return INDEX.get(id);
    }
}
