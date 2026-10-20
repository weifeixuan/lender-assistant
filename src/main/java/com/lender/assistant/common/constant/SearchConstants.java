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

    private SearchConstants() {
    }
}
