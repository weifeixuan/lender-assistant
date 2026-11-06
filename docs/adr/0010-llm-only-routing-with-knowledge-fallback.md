# ADR-0010：问题路由交给 LLM，失败一律降级知识库

- 状态：提议中（待 PRD 评审后转已采纳）
- 相关：`docs/prd/qa-routing-and-plan-execute.md §4 / §9`、`rag/service/impl/QaServiceImpl`

## 背景

现有 `POST /api/qa` 把所有问题都按知识库问答处理，无法区分：

- 与项目无关的问候、闲聊、越权问题，不应进入检索与生成；
- 需要多步比较、汇总、多跳的问题，单次检索 + 单次生成答不全。

可选方案有三种：纯规则、纯 LLM 分类、规则前置 + LLM 兜底。项目里已有的 `aitools/ToolIntentRouter` 是关键词规则，但它只服务于 Mock Function Calling 决策，不能证明语义分类能力。

## 决策

- 路由**只调用一次 LLM 结构化输出**：`route`（`IRRELEVANT` / `KNOWLEDGE` / `PLAN`）、`confidence`、`reason`、`irrelevantCategory`。
- **v1 只保留三路由**：`IRRELEVANT` / `KNOWLEDGE` / `PLAN`。
  - 曾讨论的 `SIMPLE`（开放域直答）与「问候 / 闲聊 / 与项目无关 → 固定提示，不直答」冲突，**并入 `IRRELEVANT`**；`REACT`（模型自主边思考边调工具）本项目没有真实业务系统工具，且与 ADR-0012 / ADR-0014 冲突，**本期不做**。
  - 若产品决定开放直答，`SIMPLE` 作为新增分支加进来；若未来引入业务系统工具，`REACT` 另行立项。
- **路由值 `PLAN` 与 SSE 事件 `plan` 不是一回事**：前者是分类结果，后者是计划事件。
- **未知路由值绝不抛异常**：解析到三类之外的值一律降级 `KNOWLEDGE`，与「非法 JSON / 超时 / 熔断」同等处理。
- **不提供规则版生产实现**；生产/评测只走真实模型。`rag.chat.provider=mock` 时**跳过路由与计划**，直接走现有 `KNOWLEDGE` 单步链路，事件序列与今天一致（`meta → sources → delta* → done`），**不发** `route` / `plan` / `step`；mock 只保证对话能走通，不为了联调造事件。
- `confidence < 0.6`、JSON 非法、超时、熔断，一律降级 `KNOWLEDGE`（fail-open）。路由为**非流式**调用，失败可重试 1 次（`max-attempts=2`），单次调用受代码常量 `NON_STREAM_CALL_TIMEOUT`（10s）约束，计入唯一配置 `rag.plan.total-budget-ms`（90s）的整轮预算。
- 每轮（含追问）都过路由；追问输入带上一轮用户问题与上一轮 `route`。若本轮判 `IRRELEVANT` 但上一轮是 `KNOWLEDGE` / `PLAN`，强制降级 `KNOWLEDGE`。
- **`previousRoute` 只取最近一条 `DONE` 助手消息的 `route`**；最近一条是 `FAILED` / `STOPPED` 时视为无 `previousRoute`，不做继承保护、不向前回溯。
- **固定提示轮不参与后续上下文**：`IRRELEVANT` 轮的消息以 `provider="rule"` 落库，`ConversationHistoryAssembler` 整轮跳过（`provider="rule"` 不作为回放内容，也不作为 `previousQuestion` 来源）。否则「你好」这类无关问题与固定提示会污染下一轮检索与生成。
- 计划生成是第二次 LLM 调用，只在 `PLAN` 且未降级时发生；每步的 `tool` 与 `args` 由计划调用一并输出（执行见 ADR-0014），执行步本身不调用模型。

## 理由

1. 误判 `IRRELEVANT` 等于直接拒答，代价最高；降级到 `KNOWLEDGE` 最多多一次检索，代价最低。fail-open 是唯一合理的安全方向。
2. 规则无法判断「问题是否属于本知识库」；规则只能覆盖问候等确定性场景，收益不足以承担维护成本。
3. 路由与计划分开调用：路由保持轻量，普通轮只付一次分类成本；计划只在 `PLAN` 轮发生，两段 Prompt 可独立评测。
4. mock 只作为「对话能走通」的辅助，不模拟路由与计划：一旦 mock 造出固定的 `route` / `plan` / `step`，开发环境与生产的语义就不同，形成假验证。因此 `route` / `plan` / `step` 的联调与验收**必须在真实 provider 下进行**。

## 影响

- `route` / `plan` / `step` 在 mock 模式下不会出现；这三个事件的联调与验收必须在真实 provider 下进行。
- 路由/计划准确率必须靠离线评估集与真实模型复测，不能靠规则单测证明效果。
- 新增 `LlmScene.ROUTE` / `LlmScene.PLAN` / `LlmScene.SYNTHESIS`，路由、计划与汇总合成的耗时、token 进入 `llm_call_log`。原设想的 `LlmScene.REASON` 随「执行步不调用模型」一起作废（ADR-0014）。
- 追问继承保护依赖上一轮助手消息的 `route` 落库；不落库就无法实现。

