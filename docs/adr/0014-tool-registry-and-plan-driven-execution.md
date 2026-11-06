# ADR-0014：编排层工具注册表 + 计划驱动执行，执行步只做确定性调用

- 状态：提议中（待 PRD 评审后转已采纳）
- 相关：`docs/prd/qa-routing-and-plan-execute.md §5 / §6 / §9`、ADR-0010、ADR-0012、ADR-0013

## 背景

Plan-and-Execute 需要定义「一步怎么执行」。可选模型：

1. **按步骤类型执行**：计划里用 `useKnowledge` 之类布尔标记，执行器按标记选「知识步」「推理步」两个实现；
2. **按工具执行，允许模型步**：计划里写 `kb.search` / `llm.reason` / `calc`，其中 `llm.reason` 是模型步；
3. **按工具执行，只允许确定性工具**：工具全部是确定性调用，计划执行完只做一次汇总合成。

同时还要决定：工具由**编排代码按计划调用**，还是由**模型运行时自主 Function Calling**。

## 决策

采用「编排层工具注册表 + 计划驱动执行」，并选择方案 3：

- **步骤 = 一次确定性工具调用**：`{stepId, order, title, tool, args}`；计划里没有 `useKnowledge`，也没有 `llm.reason` 这类模型步。
- **工具面最小化**：一个 `AgentTool` 接口（`name()` + `call(args, ctx)`）、一个 `ToolResult` record（`ok` / `content` / `chunkIds` / `errorCode`）、一个 `PlanToolRegistry`（`Map<String, AgentTool>` + `get(name)`）；步骤执行器按 `step.tool()` 直接取工具，不建 Factory、不再拆 `ToolSpec` / `ToolCall` / `ToolContext` 三件套。
- **工具白名单** `rag.plan.allowed-tools`：`kb.search` 必选；`customer.profile` / `lender.product` 为 **mock 业务工具**，由 `rag.plan.tools.mock-enabled` 控制（默认 `false`，本地假数据、不发网络请求、无密钥）。
- **参数计划期给定**：`args` 必须是字面量；`customerId` / `productCode` 由 planner 从问题中直接抽出，抽不出就不建该步。不支持 `"${s1.output}"` 动态引用。
- **超时收敛为「单一整轮预算 + 常量」**：对外只暴露 `rag.plan.total-budget-ms`（90s）；`NON_STREAM_CALL_TIMEOUT`（10s，路由/计划）、`TOOL_TIMEOUT`（10s，每步工具）、`SYNTHESIS_RESERVE`（20s，停止开新步的剩余阈值）是代码常量。合成超时取 `min(现有问答流超时 60s, 剩余预算)`。代价是路由与合成不能单独调参；慢模型下路由超时会降级 `KNOWLEDGE`，方向安全。
- **计划整单校验、原子弃用**：步数、白名单、`args`、至少一个 `kb.search`、`stepId` / `order` 合法性任一不满足 → 整单降级 `KNOWLEDGE`。
- **只有汇总合成流式**：执行步只产出上下文，不推 `delta`；`ToolResult` 不包含流式能力，`sources` 在所有步骤结束后一次性发送（全局并集）。
- **最终正文** = `AnswerGeneratorStrategy` 的单次汇总输出。
- **不做失败重规划**：某步 `FAILED` / `DEGRADED` 只记录，继续后续步骤；缺失信息在汇总回答里明确说明（用户已否决重新计划方案）。
- **不采纳模型运行时自主 Function Calling**（agent 循环）：模型不能自行挑工具、不能自行加步。

## 理由

1. **LLM 调用恒定**：执行步全部确定性，一轮 `PLAN` 只有「路由 1 + 计划 1 + 汇总 1」三次调用，可预算、不随步数膨胀。
2. **步骤可控**：步数、工具种类、超时都在计划期确定；运行时 FC 的步数不可预知，token 随回填膨胀。
3. **语义单一**：`sources` 是一个数组、先于首个 `delta`；只有汇总合成流式，天然满足该契约。
4. **失败边界简单**：计划生成/校验失败发生在 `sources` 与首个 `delta` 之前，可安全回退 `KNOWLEDGE`；执行步失败不中断整轮，交给汇总合成做「信息不足」说明。
5. **观测单位即步骤**：`stepId` + `tool` + 耗时 + `chunkIds` 直接落 `execution_plan`，可按工具聚合。
6. **抽象最小化**：注册表按名字查表已足够；`ToolSpec` / `ToolCall` / `ToolContext` 拆太细只增加样板代码，评审与实现成本更高。

## 代价

- 汇总 Prompt 变长：跨文档比较/汇总全部压到最终一次生成，靠 `rag.plan.max-context-chars`（默认 6000）截断。
- 首个 `delta` 更晚，前端必须用 `step` 事件表达进度。
- 计划退化为「信息点检索 + 业务数据查询的分解」，不承担分步推理。
- mock 工具默认关闭，复杂链路中的业务数据分支需要显式开启才能演示。

## 影响

- 计划 JSON、`plan` 事件、`execution_plan` 快照统一为 `tool` 语义；`useKnowledge` 与 `llm.reason` 不再出现。
- `AnswerGenerationRequest` 增加 `List<StepOutput> priorStepOutputs`；`OpenAiCompatible` / `Mock` 实现与测试同步改。
- 新增 mock 工具类与 `resources/mock/*.json`；`mock-enabled=false` 时不注册、不在白名单。
- 取代 ADR-0012 中 `PlanStepExecutorStrategy` 的表述；「检索不做模型可调用 Tool」结论保持不变。
