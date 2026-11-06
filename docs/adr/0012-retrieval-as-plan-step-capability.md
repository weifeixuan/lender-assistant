# ADR-0012：检索登记为编排层计划工具，不进入 `tools` 包的模型工具面

- 状态：提议中（待 PRD 评审后转已采纳）
- 相关：`docs/prd/qa-routing-and-plan-execute.md §10`、`rag/service/SearchService`、`tools/LenderQueryTools`、`tools/ToolIntentRouter`

## 背景

「把知识库检索抽象成工具，供计划步骤单独调用」有两个落点：

1. 放进现有 `tools/`，做成 `@Tool`（模型可自主调用）；
2. 留在 `rag/`，做成编排层 `PlanTool`（代码按计划顺序调用）。

现有 `tools/` 的定位是**模型可自主调用**的 Function Calling 面：`LenderQueryTools` 用 `@Tool` 暴露给真实模型，`ToolIntentRouter` 只是 Mock 模式下用关键词模拟「模型要不要调工具」。计划步骤的检索则是由编排代码决定何时、用哪个 query 去调。

## 决策

保留原结论，补充限定条件：

- **检索不注册为模型可自主调用的 Function Calling Tool**，不放进 `tools/`。
- **检索登记为编排层工具**：`kb.search`（`rag/tool/impl/KbSearchPlanTool`），实现 `AgentTool`，由 `PlanToolRegistry` 管理，调用方是 `PlanAndExecuteServiceImpl`（ADR-0014）。同层的 `customer.profile` / `lender.product` 只是 mock 业务工具（`mock-enabled` 默认关），与检索同样不进入 `tools/`。
- 检索的唯一实现仍是 `SearchService.hybridSearch`；`kb.search` 只是适配器，不新增检索实现。
- 原 ADR 设想的 `PlanStepExecutorStrategy`（知识 / 推理两个实现 + factory）**被 ADR-0014 的 `AgentTool` + `PlanToolRegistry` 取代**：步骤的单元从「类型」变成「工具名」。
- `tools/` 保持现状，不承载计划步骤的工具。

## 理由

1. 调用方不同：`tools/` 的调用方是模型，`PlanTool` 的调用方是编排代码；混在一起会让模型有机会绕开计划自行检索（即 ADR-0014 明确不采纳的 agent 循环模式）。
2. 语义不同：检索是确定性的、必须受总预算 / 单工具超时 / 来源编号约束；Function Calling 工具的入参由模型运行时生成，失败语义与重试策略都不同。
3. 观测不同：计划步骤的检索要按 `stepId` 统计命中率、耗时与 `sourceChunkIds`；`tools` 的调用日志以「模型决策」为单位。
4. 现有 `SearchService` 已经是检索端口，再加一层 `@Tool` 只是换名字，却把检索暴露给了模型；`PlanTool` 适配器保留的就只是「编排层如何调用检索」这一层，是必要的。

## 影响

- 计划步骤不能由模型自主决定「再搜一次」；需要重搜时必须由计划或执行器代码发起。
- 若未来确实要把检索开放给模型调用，另建 `@Tool` 适配器直接调用 `SearchService`，不复用 `PlanTool`；两套入口要各自承担超时与留痕。
- ADR 的「不做模型可调用 Tool」结论**不变**；变化只在于检索现在有了一个编排层工具名 `kb.search`，因此计划里不再出现 `useKnowledge` 布尔标记。
- 新增 `PlanTool` 后不需要 factory：注册表按工具名查表（ADR-0014）。
