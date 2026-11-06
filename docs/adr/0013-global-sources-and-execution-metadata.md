# ADR-0013：多步来源合并为全局并集，执行元数据落 `message` 的 JSONB 列

- 状态：提议中（待 PRD 评审后转已采纳）
- 相关：`docs/prd/qa-routing-and-plan-execute.md §12`、`docs/schema.sql`、`docs/adr/0006-testing-and-schema-source-of-truth.md`、`conversation/model/QaSourceSnapshot`

## 背景

Plan-and-Execute 会产生多组召回结果与每步执行状态，需要回答两个问题：

1. 多步召回的 `sources` 如何合并、编号、落库？
2. 路由结果与步骤执行结果存哪里？

可选方案：

- 每步发一个 `sources` 事件、每步独立编号；
- 合并成一个全局数组；
- 新建 `qa_execution_plan` 表存步骤状态。

## 决策

- **来源合并**：所有 `tool = kb.search` 步骤的召回按 `chunkId` 去重，按「步骤 `order` + 单步排名」分配全局 `idx`；在全部步骤执行完、汇总合成前发**一个** `sources` 数组；`message.sources` 保存同一份并集快照；`done.sourceCount` = 并集长度。`customer.profile` / `lender.product` 等 mock 业务工具 `chunkIds` 为空，不参与并集。
- **执行元数据**：`message` 新增两个可空 JSONB 列 `route`、`execution_plan`，仅 `ASSISTANT` 行写入；不新建表。`execution_plan` 顶层记录 `degraded` / `degradedReason` / `contextTruncated` / `provider` / `elapsedMillis`，`steps[]` 记录 `stepId` / `order` / `title` / `tool` / `args` / `status` / `elapsedMillis` / `sourceChunkIds` / `outputSummary` / `errorCode`；不存步骤全文。
- 历史消息接口第一阶段不返回这两个新列，保持现有 `MessageResponse` 形状不变。
- 新列同步写入 `docs/schema.sql` 并补 `COMMENT ON`。

## 理由

1. 现有跨端契约规定 `sources` 是一个数组、先于 `delta`；每步一个 `sources` 事件会破坏前端「先渲染引用面板」的假设。执行步全部是确定性工具、只有汇总合成流式，`sources` 恰好可以放在它之前。
2. 全局编号让所有步骤的【来源N】指向同一套来源，避免「步骤 1 的来源 1」与「步骤 2 的来源 1」冲突。
3. 去重只做一次，`message.sources` 与 SSE `sources` 完全同构，历史回放可以直接复用现有渲染。
4. 执行元数据与助手消息生命周期一一对应，没有按步骤独立查询的需求；新表只增加外键、索引与联表成本。
5. 历史接口不变，避免本阶段同时改动 SSE 与历史消息两个契约面。

## 影响

- 全局编号会让复杂答案中的引用编号跨度较大；产品上可接受，后续若要每步独立编号需重新设计 `sources.idx` 语义。
- 新增列后，`MessageRow`、`AssistantMessageCompletion`、Mapper XML、`docs/schema.sql` 必须同步改；只改库不回写 schema 视为提交不完整（ADR-0006）。
- `route` / `execution_plan` 是内部可观测数据，暂不对历史接口暴露；第二阶段若要展示，作为可选字段增量加入。
