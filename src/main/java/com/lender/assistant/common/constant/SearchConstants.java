package com.lender.assistant.common.constant;

/**
 * 检索相关数值常量：集中"默认召回条数"这一跨类复用的 API 默认值，避免各接口各自写死魔数（§3.3）。
 *
 * 取舍：只收「跨类共用」的默认值——它们是对外接口的策略默认（不同 controller 复用同一口径）；
 * 检索算法内部的实现细节（RRF 平滑常数、关键词 token 长度上限等）留在 SearchServiceImpl 类顶部，
 * 不往 common/constant 里塞（§3.3 规则：单类使用的提为「所在类顶部」）。
 */
public final class SearchConstants {

    /** 问答 / 评估接口缺省召回条数（QaController、EvalController 共用） */
    public static final int DEFAULT_TOP_K = 4;

    /** 检索测试入口缺省召回条数（DocumentAdminController.search，比问答多留 1 条便于人工观察） */
    public static final int ADMIN_SEARCH_DEFAULT_TOP_K = 5;

    /** 候选池放大倍数：两通道各召回 multiplier*topK 条，RRF 融合后再截断到 topK */
    public static final int CANDIDATE_MULTIPLIER = 2;

    /** 候选池下限：topK 很小时仍保留足够候选，让同 section 相邻切片有机会一起进入融合 */
    public static final int MIN_CANDIDATE_LIMIT = 12;

    /**
     * 候选池大小 = max(2*topK, 12)。
     *
     * 为什么不在 Service 内做魔数：Mapper 的 LIMIT 与服务侧期望的候选数量必须同源，
     * 跨类共用故收敛到本常量类（§3.3）。
     */
    public static int candidateLimit(int topK) {
        return Math.max(CANDIDATE_MULTIPLIER * topK, MIN_CANDIDATE_LIMIT);
    }

    private SearchConstants() {
    }
}
