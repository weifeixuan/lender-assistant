# 08: sources 并集、全局编号与落库

**阶段:** P2 计划与工具
**Blocked by:** 07
**Status:** ready-for-agent

**范围:** 多步命中的来源合并、去重、全局 `idx`，以及 `message.sources` 与 `execution_plan.steps[].sourceChunkIds` 落库。

**要做的事:** 无论计划有几步，用户看到的引用列表都是唯一的、编号连续的，且可回溯到具体步骤。

**涉及文件/类:** `PlanAndExecuteService` 来源汇总、来源快照模型、消息仓储。

- [ ] 所有步骤结束后一次性合并，按 `chunkId` 去重
- [ ] 全局 `idx` 按「步骤 `order` + 单步排名」连续分配，从 1 开始无空洞
- [ ] `sources` 只发一次且先于首个 `delta`
- [ ] `sourceCount` 等于并集长度；零命中时为 0
- [ ] mock 工具产物不进 `sources`
- [ ] `message.sources` 快照与 SSE 下发的数组逐项一致（`idx`、来源字段）
- [ ] `execution_plan.steps[].sourceChunkIds` 记录每步原始命中，不与并集混淆
- [ ] 单测：跨步重复 `chunkId`、单步多命中、全零命中

**不做什么:** 不新增来源字段；不改 `sources` 元素结构；不做引用正文回填。
