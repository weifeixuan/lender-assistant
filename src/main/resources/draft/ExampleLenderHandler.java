package com.lender.draft.generated;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {{LENDER_NAME}} 适配层（代码草稿）。
 *
 * 生成来源：字段映射表 + LenderHandler 策略骨架（阶段5）。
 * 【人工复核声明】本类由 AI 依据字段映射表生成，必须逐行人工复核、补全
 * 报文组装与签名逻辑后才能使用，禁止直接上生产。
 */
public class ExampleLenderHandler implements LenderHandler {

    private static final String GATEWAY_URL = "{{F_GATEWAY_URL}}";
    private static final String UAT_URL = "{{F_UAT_URL}}";
    private static final String PAY_PATH = "{{F_PAYMENT_API}}";
    private static final String QUERY_PATH = "{{F_QUERY_API}}";

    // 鉴权方式: {{F_AUTH_METHOD}}
    // 凭证说明: {{F_CREDENTIALS}}
    // 签名规则: {{F_SIGN_RULE}}

    /** 交易状态映射（来源: 资方文档状态枚举字段） */
    private static final Map<String, String> STATUS_MAP = new HashMap<>();
    static {
{{F_STATUS_ENUM}}
    }

    // 对账文件获取方式: {{F_FILE_DELIVERY}}
    // 对账文件格式: {{F_FILE_FORMAT}}

    @Override
    public String lenderCode() {
        return "{{LENDER_CODE}}";
    }

    @Override
    public String pay(String payload) {
        // TODO 人工实现: POST GATEWAY_URL + PAY_PATH，按上方鉴权/签名注释组装请求
        throw new UnsupportedOperationException("草稿占位：放款调用待人工实现");
    }

    @Override
    public String queryStatus(String queryNo) {
        // TODO 人工实现: GET GATEWAY_URL + QUERY_PATH，queryNo 作查询参数
        throw new UnsupportedOperationException("草稿占位：状态查询待人工实现");
    }

    @Override
    public String parseEodFile(Path file) {
        // TODO 人工实现: 按上方对账文件获取方式/格式注释解析
        throw new UnsupportedOperationException("草稿占位：对账解析待人工实现");
    }
}
