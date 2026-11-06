# ADR-0011：复用 `POST /api/qa` SSE，增量扩展 route / plan / step 事件

- 状态：提议中（待 PRD 评审后转已采纳）
- 相关：`docs/prd/qa-routing-and-plan-execute.md §11`、`docs/adr/0005-streaming-and-error-semantics.md`、`docs/prd/multi-turn-backend.md §4.4`

## 背景

问题路由与 Plan-and-Execute 需要把「问题类型」「计划」「步骤进度」告知前端。可选方案：

1. 新增独立端点 `POST /api/qa/plan`；
2. 复用 `POST /api/qa`，对前端完全透明（只在后端内部执行）；
3. 复用 `POST /api/qa`，在现有事件序列中增量加入 `route` / `plan` / `step`。

## 决策

采用方案 3：

- `meta` 仍是每轮首事件，四字段不变；`done` 仍是 `{provider, elapsedMillis, sourceCount}`，不新增字段。
- 在 `meta` 之后新增 `route`（生效后的路由结果 + 置信度）；`PLAN` 再新增 `plan` 与 `step`。
- `plan` **只发送一次**：`{degraded, goal, steps:[{stepId, order, title, tool}]}`；`steps` 只暴露必要字段，不暴露 `args`。
- `sources` 仍是**一个数组、先于首个 `delta`**；复杂轮次为所有 `kb.search` 步骤的并集去重结果，元素结构不变；mock 业务工具不产出来源。
- 计划只读可见，不支持人工改写，也不做失败重规划；某步失败只发 `step(FAILED/DEGRADED)` 并继续。
- 前端对未知事件必须忽略；新增事件用于可选的进度展示，不阻塞现有渲染。
- `rag.chat.provider=mock` 时**不发** `route` / `plan` / `step`（mock 跳过路由与计划，只走 `KNOWLEDGE` 单步链路）；这三个事件的联调与验收**必须在真实 provider 下进行**，mock 只保证对话能走通。

## 理由

1. 会话懒创建、`roundId`、会话锁、消息落库、`STOPPED` 语义全部绑定在 `/api/qa` 上；新端点会复制一整套并发与存储逻辑。
2. `meta` 首事件与 `done` 三字段是现有跨端契约；保持不动，前端无需改核心流程。
3. `sources` 在首个 delta 前一次性到达，才能先渲染引用面板；因此复杂链路采用「先执行全部检索类工具，最终合成前发一个 sources」，而不是每步各发一个 sources 数组。
4. 新增事件是增量契约：不知道 `route` / `plan` / `step` 的前端仍能只看 `meta` / `sources` / `delta` / `done`。

## 影响

- 现有前端若断言「`meta` 后紧跟 `sources`」，需要更新；按事件名分发的解析器可不动。
- 复杂链路只有汇总合成阶段推 `delta`，是一段连续流；执行步骤只发 `step` 事件，前端不能再靠 `delta` 观察每步正文。
- 首个 `delta` 会比现状更晚（先跑完全部确定性工具），前端需要用 `step` 事件表达进度。
- `done` 三字段不变；`IRRELEVANT` 轮没有模型生成，`provider` 固定为 `"rule"`，前端如需展示模型名要做映射。
- 前端 PRD 需要同步补充 `route` / `plan` / `step` 的渲染与容错规则（本 ADR 不改前端仓库）。
- 历史消息接口第一阶段不暴露 route/plan，避免同时改动两个契约面。
