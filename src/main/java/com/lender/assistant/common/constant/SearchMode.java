package com.lender.assistant.common.constant;

/**
 * 检索模式标识：{@code SearchService.search} 的 mode 参数，也是 /api/admin/eval/run 的实验开关。
 *
 * code 即请求体 JSON 里的取值标签。服务层内部直接比较枚举，不再逐处比对字符串；
 * 只有 JSON 边界（EvalController）用 {@link #fromCode(String)} 把字符串解析成枚举。
 */
public enum SearchMode {

    /** 双通道合并（RRF，生产路径，默认值） */
    HYBRID("hybrid", "双通道合并（RRF）"),

    /** 只走向量通道（评估单通道贡献） */
    VECTOR("vector", "仅向量通道"),

    /** 只走关键词通道（评估单通道贡献） */
    KEYWORD("keyword", "仅关键词通道");

    private final String code;
    private final String desc;

    SearchMode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String code() {
        return code;
    }

    public String desc() {
        return desc;
    }

    /**
     * JSON 边界的 mode 字符串 → 枚举。
     * 缺省（null / 空白）取 HYBRID（与原 controller 默认值一致）；
     * 未识别的值直接拒绝并抛 {@link IllegalArgumentException}，由全局处理器落 400——
     * 静默回落某个模式会让评估结论不可信（§5.2 入参必须校验）。
     */
    public static SearchMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return HYBRID;
        }
        for (SearchMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知检索模式: " + code);
    }
}
