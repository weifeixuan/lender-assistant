# 问题路由与 Plan-and-Execute Spec（后端实现）

Status: ready-for-agent

事实源：[docs/prd/qa-routing-and-plan-execute.md](../../docs/prd/qa-routing-and-plan-execute.md)、ADR-0010 ~ ADR-0014。冲突时以 ADR 为准。

## Problem Statement

现有 `POST /api/qa` 把所有问题都按知识库问答处理：问候、闲聊、越权问题照样检索并生成，用户最终只得到「没有检索到相关内容」；比较、汇总、多跳类问题单次检索 + 单次生成答不全；前端也无法知道系统判成了什么类型、计划是什么、哪一步没召回。用户因此无法区分「问错了」与「资料里没有」，也不敢相信复杂问题得到的答案。

## Solution

在现有问答链路前置一层问题路由，并为复杂问题增加线性计划执行：

- 每轮先用一次非流式 LLM 调用判定 `IRRELEVANT` / `KNOWLEDGE` / `PLAN`，判定失败一律降级 `KNOWLEDGE`（fail-open），绝不因分类失败而拒答。
- `IRRELEVANT` 直接返回固定提示，不检索、不生成；该轮以 `provider="rule"` 落库，且不进入后续历史回放。
- `KNOWLEDGE` 保持现有单次检索 + 单次生成链路，只多一个 `route` 事件。
- `PLAN` 生成 2–5 步线性计划，每步是一次确定性工具调用（`kb.search` / 两个可选 mock 业务工具），全部步骤结束后仅做一次流式汇总合成。执行步不调用模型、不推 `delta`。
- 复用 `POST /api/qa` 的 SSE 契约，增量加入 `route` / `plan` / `step` 事件；`meta` 四字段、`done` 三字段、`sources` 元素结构均不变。
- 整轮只暴露一个超时配置 `rag.plan.total-budget-ms`，其余时限为代码常量；预算不足时跳过剩余步骤并强制进入合成，属设计的兜底路径。

## User Stories

1. 作为提问用户，我希望问候和闲聊不再触发检索与生成，这样我能立刻得到明确的范围提示，而不是一段「没有检索到相关内容」。
2. 作为提问用户，我希望问「那二套呢」这类指代型追问时系统知道上一轮在问什么，这样追问不会因为判成无关而被误杀。
3. 作为提问用户，我希望比较两家资方接口差异的问题能得到分点、可核对的回答，这样我不需要自己拼多次问答。
4. 作为提问用户，我希望回答同时引用知识库资料与业务数据时，引用编号仍然连续可读，这样我能逐条核对依据。
5. 作为提问用户，我希望某个信息点查不到时回答明确说明缺哪块，而不是编造，这样我知道结论的边界。
6. 作为提问用户，我希望回答再慢也不要无限等待，这样超时前我能拿到已生成的部分或明确的失败提示。
7. 作为提问用户，我希望失败的轮次在历史里保留并可重试，这样我不用重新输入问题。
8. 作为前端开发者，我希望第一个事件仍是 `meta` 且四字段不变，这样会话创建与消息 id 绑定的逻辑不用改。
9. 作为前端开发者，我希望 `done` 仍只有三个字段，这样收尾处理不用改。
10. 作为前端开发者，我希望 `sources` 仍是一个数组、先于首个 `delta`，这样引用面板可以照旧渲染。
11. 作为前端开发者，我希望 `route` 告诉我本轮判成了什么类型，这样我可以在复杂问题上先展示「正在规划」。
12. 作为前端开发者，我希望 `plan` 只发一次并给出步骤标题与工具名，这样我可以整体渲染计划而不必处理增量变更。
13. 作为前端开发者，我希望 `step` 提供运行中与终态，这样首个 `delta` 到来前我能表达进度。
14. 作为前端开发者，我希望未知事件被忽略即可，这样前端升级可以解耦。
15. 作为前端开发者，我希望 `IRRELEVANT` 轮的 `provider` 明确是 `rule`，这样我可以把它展示成规则提示而不是模型名。
16. 作为前端开发者，我希望 mock 模式下事件序列与现状完全一致，这样本地联调不被新事件干扰。
17. 作为运维/开发者，我希望只有一个整轮预算配置，这样时延调参不需要同时理解五个参数。
18. 作为运维/开发者，我希望路由或计划调用超时后自动降级而不是报错，这样模型抖动不会变成用户侧失败。
19. 作为运维/开发者，我希望每个步骤的失败只影响该步、不中断整轮，这样单点工具故障不会让回答完全失败。
20. 作为运维/开发者，我希望每轮的 LLM 调用次数恒定（`PLAN` 恒为 3 次），这样成本可预估。
21. 作为运维/开发者，我希望路由结果、计划快照与步骤耗时落库，这样可以用 SQL 观察分布与失败率。
22. 作为评测者，我希望路由与计划各有评估集入口，这样阈值与步骤上限可以按数据校准。
23. 作为评测者，我希望指代型追问用例的验收动作是「计划不得引入与当前问题无关的步骤」，这样 prompt 分块是否生效可以被判定。
24. 作为维护者，我希望 mock 模式不伪造 `route` / `plan` / `step`，这样开发环境不会产生与生产语义不同的假验证。
25. 作为维护者，我希望工具超时由工具自身决定并受一个全局上限兜底，这样新增工具漏配也不会挂死线程。

## Implementation Decisions

### 领域模型与术语

- **问题路由**：每轮一次非流式 LLM 结构化判定，输出 `route`、`confidence`、`reason`、`irrelevantCategory`。
- **路由值**三选一：`IRRELEVANT`（不使用知识库且与资方接入无关）、`KNOWLEDGE`（单次检索 + 单次生成可覆盖）、`PLAN`（多信息点或跨文档比较/汇总）。
- **路由值 `PLAN` 与 SSE 事件 `plan` 是两回事**：前者是分类结果，后者是计划事件；命名不得互相替代。
- **`AgentTool`**：最小工具抽象，只有 `name()` 与 `call(args, ctx)` 两部分。
- **`ToolResult`**：`ok` / `content` / `chunkIds` / `errorCode`；不携带流式能力。
- **`PlanToolRegistry`**：`Map<String, AgentTool>` + `get(name)`；按计划中的 `tool` 直接取实现，不建 Factory。
- **步骤状态**：`PENDING` / `RUNNING` / `DONE` / `DEGRADED` / `FAILED` / `SKIPPED`，仅在执行态与 `execution_plan` 快照中存在，不写入 `message.status`。
- **sources 并集**：所有产生 `chunkIds` 的步骤结束后一次性合并，按 `chunkId` 去重，按「步骤 `order` + 单步排名」分配全局 `idx`；`sourceCount` 等于并集长度。

### 接口契约

`POST /api/qa` 请求体与同步校验边界不变：`conversationId?`、`question`、`topK?`；`400/404/409` 全部在返回 `SseEmitter` 之前同步抛出，是普通 JSON 错误体，不是 SSE 流。`X-User-Id` 校验与并发锁沿用多轮会话既有实现。

事件序列（按路由分三条）：

| 路由 | 事件序列 |
|---|---|
| `IRRELEVANT` | `meta → route → sources([]) → delta(固定提示) → done(sourceCount=0, provider="rule")` |
| `KNOWLEDGE` | `meta → route → sources → delta* → done` |
| `PLAN` | `meta → route → plan(一次) → step* → sources(一次) → delta* → done` |

载荷：

- `meta`：`{conversationId, roundId, userMessageId, assistantMessageId}`，每轮首事件，不变。
- `route`：`{route, confidence}`；`reason` 与 `irrelevantCategory` 只落库/日志，不进 SSE。
- `plan`：`{degraded, goal, steps:[{stepId, order, title, tool}]}`，**只发一次**；不暴露 `args`；降级时 `degraded=true, goal="", steps=[]`。
- `step`：`{stepId, status, elapsedMillis}`；状态为 `RUNNING` 与 `DONE` / `DEGRADED` / `FAILED` / `SKIPPED`。
- `sources`：结构不变，`PLAN` 轮为多步并集；**只发一次且先于首个 `delta`**。
- `delta`：纯文本，仅汇总合成阶段，一段连续流；任何执行步都不产生 `delta`。
- `done`：`{provider, elapsedMillis, sourceCount}`，不变；`IRRELEVANT` 轮 `provider` 固定为 `"rule"`。
- `error`：纯文本；之后断流。

`mock` 模式（`rag.chat.provider=mock`）跳过路由与计划，直接走 `KNOWLEDGE` 单步链路，事件序列为 `meta → sources → delta* → done`，**不发** `route` / `plan` / `step`。这三个事件的联调与验收必须在真实 provider 下进行。

### 数据模型

`message` 表新增两个可空 JSONB 列，仅 `ASSISTANT` 行写入：

- `route`：路由结果快照（`route`、`confidence`、`reason`、`irrelevantCategory`）。
- `execution_plan`：计划与执行快照，字段为 `degraded`、`degradedReason`、`provider`、`elapsedMillis`、`contextTruncated`、`steps[]`。

`steps[]` 每项含：`stepId`、`order`、`title`、`tool`、`args`、`status`、`elapsedMillis`、`sourceChunkIds`、`outputSummary`、`errorCode`。

枚举取值：`errorCode` ∈ `TOOL_NOT_FOUND` / `ARGS_INVALID` / `TOOL_TIMEOUT` / `TOOL_FAILED` / `TOOL_DEGRADED`；`degradedReason` ∈ `PLAN_INVALID` / `BUDGET_EXHAUSTED`。

`docs/schema.sql` 是唯一事实源（ADR-0006）：新增列必须使用幂等 `ADD COLUMN IF NOT EXISTS`，并补 `COMMENT ON COLUMN`；空库加载后可直接启动。历史消息接口第一阶段不返回这两列，`MessageResponse` 形状不变。

### 状态机

轮次（助手消息）：

- `KNOWLEDGE`：受理 → 检索 → 生成 → `DONE`；模型异常 → `FAILED`；客户端断开 → `STOPPED`。
- `IRRELEVANT`：受理 → 固定提示落库 → `DONE`（`provider="rule"`）。
- `PLAN`：受理 → 路由 → 计划 → 计划校验通过 → 步骤串行 → 合成 → `DONE`；计划校验失败 → 降级 `KNOWLEDGE`；合成超时/异常 → `FAILED`（保留已推正文）；客户端断开 → `STOPPED`。

步骤：`PENDING → RUNNING → DONE | DEGRADED | FAILED | SKIPPED`。`FAILED` / `DEGRADED` / `SKIPPED` 均不重跑、不重新计划，继续后续步骤或直接进入合成。

### 工具契约

| 工具 | 参数 | 返回 | 来源 |
|---|---|---|---|
| `kb.search` | `{query: ≤200 字, topK?: 1–20}` | 命中列表 + `chunkIds` | 真实检索（`SearchService.hybridSearch(query, null, topK)`） |
| `customer.profile` | `{customerId: "C\d{5}"}` | 授信总额/已用/可用、风险等级、合作状态摘要 | mock 本地数据 |
| `lender.product` | `{productCode}` | 费率、期限、放款条件、支持的接口类型摘要 | mock 本地数据 |

- `kb.search` 的 `query` 由计划器写成**自包含**查询，执行时 `previousQuestion` 传 `null`，不得二次拼接上一轮问题。
- mock 工具读取 `resources/mock/customer-profiles.json` 与 `resources/mock/lender-products.json`，不发网络请求、不需要密钥，`chunkIds` 为空。
- mock 工具默认关闭：`rag.plan.tools.mock-enabled=false`；关闭时不在白名单、不注册，计划里出现即整单降级。
- `args` 不合法 → 该步 `FAILED`（`ARGS_INVALID`），不抛异常；mock 工具超时 2s、不重试。
- 单步工具超时受 `TOOL_TIMEOUT`（10s）约束，mock 工具 2s 在其内部处理。

### 计划表示与校验

计划为有序线性列表，`order` 从 1 连续递增，`stepId` 为 `s1`–`sN`，步骤数 2–5，`args` 全部是计划期字面量。必须至少有一个 `kb.search`；抽不出 `customerId` / `productCode` 就不建该步。v1 不设 `dependsOn`。

计划输入 = 当前问题 + 上一轮用户问题 + 知识库目录（仅 `docId` + `docTitle`）+ 工具清单。上一轮问题只用于解析指代，`goal` 必须是本轮目标。计划 Prompt 必须分成「上一轮问题（仅用于解析指代，不要并入本轮目标）」与「当前问题（本轮目标）」两段，并声明「当前问题可独立理解时忽略上一轮问题」。

整单原子校验，任一不满足即弃用：步数越界、`tool` 不在白名单、`args` 校验失败、无 `kb.search`、`stepId` / `order` 重复或乱序、JSON 非法 / 超时 / 熔断。弃用后发 `plan(degraded=true, goal="", steps=[])` 并按 `KNOWLEDGE` 执行。

### 路由决策

路由输入：当前问题、上一轮用户问题、上一轮 `route`、知识库目录。`previousRoute` 只取最近一条状态 `DONE` 的助手消息；最近一条为 `FAILED` / `STOPPED` 时视为无 `previousRoute`，不向前回溯。

降级规则：路由值不在三类内、JSON 非法、超时、熔断、`confidence < 0.6` → `KNOWLEDGE`，不发 `error`。

追问保护：本轮判 `IRRELEVANT` 但 `previousRoute` 为 `KNOWLEDGE` / `PLAN` → 强制 `KNOWLEDGE`。

固定提示：由 `rag.routing.irrelevant-reply` 单一控制；该轮 `sources=[]`、`sourceCount=0`、消息 `DONE`、`done.provider="rule"`。固定提示轮整轮跳过历史回放，也不作为 `previousQuestion` 来源。

`[待确认]` 无关轮是否排除历史回放：当前按「排除」落地（`provider="rule"` 整轮跳过）。

### 预算与超时

- 唯一配置：`rag.plan.total-budget-ms=90000`，从路由调用开始计时，覆盖到合成结束，包含路由/计划/步骤/合成以及所有重试。
- 代码常量：`NON_STREAM_CALL_TIMEOUT=10s`（路由与计划单次非流式调用）、`TOOL_TIMEOUT=10s`（每步工具调用）、`SYNTHESIS_RESERVE=20s`（剩余预算低于此值不再开新步）。
- 合成超时 = `min(现有问答流超时 60s, 剩余预算)`，复用既有能力。
- 提前收尾：剩余预算 < `SYNTHESIS_RESERVE` 时跳过剩余步骤（`SKIPPED`），立即进入合成；这是兜底路径，不是异常。
- 重试：非流式 LLM 调用沿用 `rag.resilience.max-attempts=2`（重试 1 次），检索重试 1 次，均计入整轮预算。
- SSE 超时为硬编码 120s，整轮 90s 结束保留 30s 余量。
- `[待确认]` 线程池是否改可配置 16/32：当前按现状 8 线程 / 队列 100 描述，16/32 只是建议文字。

### 失败矩阵

| 触发条件 | 用户可见结果 | 落库状态 | SSE 收尾 |
|---|---|---|---|
| 路由超时 / 熔断 / 非法 JSON / 低置信 | 正常回答（走知识库） | `DONE` | `route(KNOWLEDGE)` 后继续 |
| 本轮判无关但上一轮为知识/计划 | 正常回答（走知识库） | `DONE` | 同上 |
| 计划校验失败 / 超时 / 非法 | 正常回答（走知识库） | `DONE` | `plan(degraded=true, steps=[])` |
| 工具不存在 / `args` 非法 | 回答中说明该信息点缺失 | `DONE` | `step(FAILED)`，继续 |
| `kb.search` 零召回 | 回答中说明「资料中未提及」 | `DONE` | `step(DEGRADED)`，继续 |
| 预算在步骤边界耗尽 | 用已得信息合成回答 | `DONE`（`BUDGET_EXHAUSTED`） | `step(SKIPPED)*` 后 `sources → delta → done` |
| 剩余预算 < `SYNTHESIS_RESERVE` | 跳过剩余步骤后合成 | `DONE`（`BUDGET_EXHAUSTED`） | 不发新事件，直接 `sources → delta → done` |
| 剩余时间不足一次合成 | 明确失败提示 | `FAILED` | `error` |
| 汇总合成超时 / 异常 | 保留已推正文 + 错误 | `FAILED` | `error` |
| 客户端断开 | 已生成内容保留 | `STOPPED` | 停止推送 |
| 线程池拒绝 | 服务繁忙提示 | `FAILED` | 同步 500 JSON |

## Testing Decisions

- 最高测试缝是 **HTTP + SSE 协议**：用 MockMvc 发请求，断言状态码、错误码、事件顺序与载荷字段；不为编排逻辑新造更低的私有抽象。
- 数据库验证分两档：读取 `docs/schema.sql` 断言新增列、可空性与 `COMMENT ON` 存在（契约级，不证明实际拒绝写入）；真库约束验证继续暂缓，沿用既有多轮票 11 的做法。
- 服务层单测替换 LLM 策略、工具注册表与线程池，覆盖：路由降级、追问保护、计划整单校验、步骤失败继续、预算收尾、`provider="rule"` 跳过回放、`sources` 并集去重与全局 `idx`。
- mock 模式单测断言事件序列不含 `route` / `plan` / `step`。
- 不测试私有方法、不断言 SQL 字符串细节、不为 `SseEmitter` 增加自定义抽象层。
- 先例：现有 `QaServiceImpl` 测试（假 emitter + 假策略）与控制器 MockMvc 测试，新用例沿用同一风格，测试名表达外部行为。

### 验收标准

1. 给定 `IRRELEVANT` 问题 → 事件恰为 `meta → route → sources([]) → delta(固定提示) → done`，`done.provider="rule"`、`sourceCount=0`，消息 `DONE`。
2. 给定 `KNOWLEDGE` 问题 → 事件为 `meta → route → sources → delta* → done`，除新增 `route` 外行为与现状一致。
3. 给定 `PLAN` 问题 → `plan` 恰好出现一次且 `steps` 长度在 2–5、`tool` 均属白名单；每个步骤都有 `RUNNING` 与终态事件；执行阶段不出现 `delta`。
4. 给定路由返回非法 JSON / 超时 / `confidence=0.5` → 仍返回正常回答，不发 `error`，`route` 事件为 `KNOWLEDGE`。
5. 给定上一轮为 `KNOWLEDGE` 且本轮判 `IRRELEVANT` → 实际按 `KNOWLEDGE` 执行。
6. 给定计划只有 1 步或含白名单外工具 → `plan(degraded=true, steps=[])` 且按 `KNOWLEDGE` 执行。
7. 给定某步工具抛异常或 `args` 非法 → 该步 `step(FAILED)` 后继续执行后续步骤，整轮仍 `DONE`。
8. 给定 5 步计划且总耗时接近预算 → 剩余不足 `SYNTHESIS_RESERVE` 时剩余步骤为 `SKIPPED`，并仍以 `done` 收尾且 `execution_plan.degradedReason="BUDGET_EXHAUSTED"`。
9. 给定多步均命中知识库 → `sources` 只出现一次，`chunkId` 去重，`idx` 连续，`sourceCount` 等于数组长度。
10. 给定 mock 工具启用 → 其产物进入合成上下文但不产生 `sources` 条目。
11. 给定 `rag.chat.provider=mock` → 事件序列无 `route` / `plan` / `step`。
12. 给定 `FAILED` / `STOPPED` 轮后追问 → 该轮不作为 `previousRoute` / `previousQuestion` 来源。
13. 给定 `provider="rule"` 轮后继续提问 → 该轮不进入历史回放。
14. 给定客户端中途断开 → 消息终态 `STOPPED`，会话锁释放。
15. 给定合并后的 schema 在空库执行 → 启动成功，`message.route` 与 `message.execution_plan` 可空且已加注释。
16. 给定计划评估集中的指代型追问 → 计划不得引入与当前问题无关的步骤。
17. `mvn test` 全绿，单测不连真库、不依赖真实模型 key。

## Out of Scope

- DAG / 并行步骤、失败重规划、人工编辑计划。
- 模型运行时自主 Function Calling（agent 循环）。
- 新增问答端点；复用 `POST /api/qa`。
- 检索算法、RRF 融合与相邻块合并的改动。
- Spring AI、多模型路由、按场景切换模型供应商。
- `PLAN` 使用完整多轮历史；跨两轮以上指代本版不解决（预期回退 `KNOWLEDGE`）。
- 历史消息接口返回 `route` / `execution_plan`（第一阶段不暴露）。
- 前端进度渲染实现（由前端需求文档另行约束）。
- 真实业务系统接口接入；`customer.profile` / `lender.product` 保持 mock。

## Further Notes

- 与 PRD/ADR 的差异：未发现规则冲突。PRD §4.3 / §7 / §9 已分别同步 ADR-0010 / ADR-0011 / ADR-0014 的三处口径（mock 不发新事件、超时收敛为单一预算）。
- 计划 Prompt 的失败表现可目视发现：`goal` 混入上一轮词，或凭空多一步检索上一轮话题；`goal` 会落库且随 `plan` 事件下发。
- 已知限制：指代跨两轮以上时只带上一轮不够。
- 已知限制：超时收敛后路由与合成不能单独调参；慢模型下路由超时会降级 `KNOWLEDGE`，方向安全。
- 已知限制：`PLAN` 轮最长占用一个流式工作线程约 90s，并发上限等于池大小。
