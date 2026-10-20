package com.lender.assistant.common.util;

/**
 * topK 入参校验（无状态静态方法，§1）。抽出的理由：QaController / EvalController /
 * DocumentAdminController 三处是同一段规则，按 §3.2 Rule of Three 收敛到单一实现（§3.6）。
 *
 * 口径：未传（null）走接口自带的默认值；传了非正数直接拒绝——
 * 避免下游 subList / LIMIT 用非法值越界。非整数由 Jackson 反序列化阶段拦下（转 4xx），
 * 故这里只需处理 null 与非正数。
 */
public final class TopKUtils {

    private TopKUtils() {
    }

    /**
     * @param raw      请求体里的 topK，可能为 null（未传）
     * @param fallback 未传时的接口默认值（各接口不同，由调用方经常量传入）
     * @return 校验通过的 topK
     * @throws IllegalArgumentException topK 非正数
     */
    public static int resolve(Integer raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw <= 0) {
            throw new IllegalArgumentException("topK 必须为正整数");
        }
        return raw;
    }
}
