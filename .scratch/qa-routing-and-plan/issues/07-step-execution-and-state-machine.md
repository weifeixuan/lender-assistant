# 07: 步骤执行、step 事件与步骤状态机

**阶段:** P2 计划与工具
**Blocked by:** 06、04
**Status:** ready-for-agent

**范围:** 按 `order` 串行执行计划步骤，推 `step` 事件，记录步骤终态。

**要做的事:** 前端能看到每一步开始与结束；单步失败不影响后续步骤和整轮收尾。

**涉及文件/类:** `PlanAndExecuteService`（执行部分）、步骤状态枚举、`step` SSE 载荷、`PlanToolRegistry`。

- [ ] 每步先发 `step(RUNNING)`，结束后发 `step(DONE / DEGRADED / FAILED / SKIPPED)`，载荷 `{stepId, status, elapsedMillis}`
- [ ] 步骤状态流转：`PENDING → RUNNING → DONE | DEGRADED | FAILED | SKIPPED`
- [ ] 单步失败或降级只记录结果，继续后续步骤；不重跑、不重新计划
- [ ] 错误码固定集合：`TOOL_NOT_FOUND` / `ARGS_INVALID` / `TOOL_TIMEOUT` / `TOOL_FAILED` / `TOOL_DEGRADED`
- [ ] `kb.search` 零召回 → `DEGRADED`；异常/超时 → `FAILED`
- [ ] 执行阶段不推任何 `delta`
- [ ] 单测：中途失败仍继续、未知工具、超时，步骤耗时与状态序列正确

**不做什么:** 不做并行执行；不做步骤级重试；不做计划变更事件。
