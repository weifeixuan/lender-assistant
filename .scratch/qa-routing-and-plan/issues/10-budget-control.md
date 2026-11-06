# 10: 预算控制与提前收尾

**阶段:** P3 失败与预算
**Blocked by:** 09
**Status:** ready-for-agent

**范围:** 整轮预算计时、三个代码常量、剩余不足时的收尾判定。

**要做的事:** 整轮不会拖过 SSE 时限；预算不够时用已得信息给出回答，而不是硬跑到超时。

**涉及文件/类:** 预算计时组件、`rag.plan.total-budget-ms`、常量 `NON_STREAM_CALL_TIMEOUT` / `TOOL_TIMEOUT` / `SYNTHESIS_RESERVE`、`PlanAndExecuteService`。

- [ ] 唯一超时配置为 `rag.plan.total-budget-ms=90000`，从路由开始计时到合成结束
- [ ] 三个常量为代码常量，不进配置表：`NON_STREAM_CALL_TIMEOUT=10s`、`TOOL_TIMEOUT=10s`、`SYNTHESIS_RESERVE=20s`
- [ ] 路由/计划的重试、检索重试全部计入同一预算
- [ ] 剩余预算 < `SYNTHESIS_RESERVE` 时，尚未开始的步骤标 `SKIPPED` 并立即进入合成
- [ ] 该情形仍发 `done`，消息 `DONE`，`execution_plan.degradedReason="BUDGET_EXHAUSTED"`
- [ ] 剩余时间连一次合成都不够 → `error` + 消息 `FAILED`
- [ ] 单测用可控时钟覆盖：顺利 40s、最坏 60s、剩余不足、无合成时间

**不做什么:** 不新增分场景超时参数；不做动态预算分配；不改 SSE 120s 常量。
