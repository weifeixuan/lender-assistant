# 06: 计划器（prompt 分块与整单校验）

**阶段:** P2 计划与工具
**Blocked by:** 04（需要工具清单与白名单）
**Status:** ready-for-agent

**范围:** 计划生成服务与计划 Prompt，新增 `LlmScene.PLAN`。不做执行。

**要做的事:** 复杂问题被拆成 2–5 个确定性工具步骤，`goal` 只描述本轮目标；指代型追问能借上一轮解析实体，但不会把上一轮话题并进本轮。

**涉及文件/类:** `PlanAndExecuteService`（计划生成部分）、`LlmScene.PLAN`、计划 Prompt 资源、计划 DTO。

- [ ] Prompt 分两段且显式标注：`上一轮问题（仅用于解析指代，不要并入本轮目标）` 与 `当前问题（本轮目标）`；并含「当前问题可独立理解时忽略上一轮问题」
- [ ] 计划输入 = 当前问题 + 上一轮用户问题 + 知识库目录（仅 `docId` + `docTitle`）+ 工具清单
- [ ] 输出 `{goal, steps[]}`；`goal` 为本轮目标；步骤数 2–5，`order` 从 1 连续，`stepId` 为 `s1`–`sN`
- [ ] `args` 全部为计划期字面量；抽不出 `customerId` / `productCode` 就不建该步
- [ ] 至少一个 `kb.search`；`tool` 必须在白名单内
- [ ] 整单原子校验：步数越界、工具越界、`args` 非法、无 `kb.search`、`stepId` / `order` 重复或乱序、JSON 非法 / 超时 → 返回弃用结果（`degraded=true, goal="", steps=[]`）
- [ ] 单次调用受 `NON_STREAM_CALL_TIMEOUT`（10s）约束，可重试 1 次，计入整轮预算
- [ ] 单测：指代型追问的计划不引入无关步骤；参数抽不出时该步缺席；非法计划全部降级

**不做什么:** 不做 DAG / `dependsOn`；不做重规划；不执行工具；不发 SSE。
