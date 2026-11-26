package com.lender.assistant.conversation.service;

/**
 * 滚动摘要服务（PRD §4）：异步触发 + 检测被裁前缀并与旧摘要滚动合并，硬截断后原子写回两列。
 *
 * 失败静默降级：提交被拒、锁竞争、模型调用或写回任何异常都只记日志、保留旧摘要，
 * 绝不向上抛——摘要不挡回答主链路（PRD §2.5）。生成与写回不在事务内（§6.11 红线）。
 */
public interface RollingSummaryService {

    /**
     * 一轮回答正常收尾（done 已发、emitter complete、会话锁已释放）后由回答工作线程调用：
     * 提交异步摘要任务后立即返回。提交与执行全程不向上抛：池拒绝静默跳过，
     * 锁竞争（新一轮正在回答）让路跳过——两个跳过方向都靠下轮幂等补齐。
     */
    void submitAfterRound(String conversationId, String userId);

    /**
     * 检测并合并滚动摘要（异步任务的实际执行体，由本服务在持锁后调用）：
     * 读会话全部消息 → 复用装配器实算完整轮序列与回放窗口起点 →
     * 被裁前缀最后一个 roundId 为新边界；新边界与库中已存边界相同即幂等出口（不调模型）；
     * 否则合并输入 = 现有摘要（如有）+ 边界之后的被裁轮次原文（天然覆盖窗口调小的间隙轮），
     * 调摘要模型生成 → 硬截断到 {@code rag.qa.summary.max-chars} → {@code updateSummary} 原子写回。
     */
    void detectAndMerge(String conversationId, String userId);
}
