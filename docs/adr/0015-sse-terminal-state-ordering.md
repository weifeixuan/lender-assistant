# ADR-0015：SSE 终态先落库再发 `done`，零召回与无关路径统一 `provider=rule`

- 状态：已采纳（2026-11-08）
- 相关：`rag/service/impl/QaEventEmitter`、`rag/service/impl/QaServiceImpl`、`rag/service/impl/RoundStateServiceImpl`、ADR-0005、ADR-0011、ADR-0013

## 背景

`/api/qa` 有三条「不调用模型」或「没有可引用来源」的终止路径：`IRRELEVANT`（无关问题）、`KNOWLEDGE` 零召回、`PLAN` 零召回。三处实现顺序与语义不一致：

1. `IRRELEVANT` 先落库终态，再发 `done`；
2. `noRecall` 先发 `done`，再落库终态；
3. `PLAN` 零召回仍进入合成，只有「无来源且无业务工具产物」才转为无召回；有业务工具产物时会调用一次合成模型。

由此产生三个问题：

- **`done` 语义不可靠**：前端收到 `done` 后会认为本轮已完成，但落库可能失败或尚未发生；进程在同一步崩溃时，库里没有对应的终态。
- **provider 失真**：零召回时 `done.provider` 报的是模型名，但本轮根本没调用模型。
- **无来源仍生成**：`PLAN` 轮零命中时，模型只能依据业务工具产物或自身知识自由发挥，是幻觉的高发区，还多付一次调用成本。

## 决策

1. **统一收尾顺序**：所有终止路径都按
   `sources([]) → delta → provider → 落库终态（state.done）→ done 事件 → complete` 执行。
   `done` 只在终态成功落库之后发送，表示「本轮已完成且已持久化」。
2. **零召回与无关路径 `provider` 恒为 `rule`**：引导文案与固定提示是规则产出，不是模型产出；只有真正调用模型的路径才在 `done.provider` 报模型名。
3. **`PLAN` 零召回在编排层统一短路**：`sources` 之后 `hits.isEmpty()` 一律走 `noRecall`，不再调用合成模型；合成器内部的零召回兜底删除，避免同一判断散落两处。
4. **失败路径不冒充完成**：落库失败或生成异常时走 `error + FAILED`，不发 `done`。

改动后的完整事件序列：

| 路径 | 事件序列 |
|---|---|
| `IRRELEVANT` | `meta → route → sources([]) → delta(固定提示) → 落库 → done(rule) → complete` |
| `KNOWLEDGE` 零召回 | `meta → route → sources([]) → delta(引导) → 落库 → done(rule) → complete` |
| `PLAN` 零召回（含业务工具产物） | `meta → route → plan → step×N → sources([]) → delta(引导) → 落库 → done(rule) → complete` |
| `PLAN` 正常 | `… → sources(并集) → delta(模型) → 落库 → done(模型名) → complete` |

## 被否决的替代方案

- **保持各路径顺序不一致**：实现成本最低，但 `done` 的语义随路径漂移，客户端无法据此判断终态是否已持久化；后续每加一条收尾路径都要重新约定顺序。
- **零召回仍调用合成模型**（包括存在业务工具产物时）：模型在没有任何引用来源时容易补充训练知识或编造；同时多一次计费调用。安全性优先。
- **零召回走 `error` / `FAILED`**：语义错误。「没有检索到内容」是正常业务结果，不是故障；前端会按错误状态渲染，用户也会被误导去重试。
- **`done` 先发、落库异步补偿**：与「`done` = 终态已落库」的契约冲突；进程崩溃或补偿失败时，前端已显示完成而库中仍是进行中。

## 后果与代价

- **业务工具产物不再合成摘要**：`PLAN` 轮若 `kb.search` 零命中、但 `customer.profile` 等业务工具恰好有值，用户只会看到「资料库中没有检索到相关内容」的引导文案，工具产物的摘要被放弃。这是换取「无来源不生成」的明确代价。
- 零召回轮次少一次模型调用，整轮更快、成本更低。
- `done` 事件成为可靠的终态信号；落库失败时用户看到 `error`，不会看到「假完成」。
- 前端不需要区分零召回来自 `KNOWLEDGE` 还是 `PLAN`，统一按 `delta + done` 渲染。

## 影响

- `QaEventEmitter.noRecall` 去掉 `provider` 入参并固定 `rule`；`QaServiceImpl` 把零召回判断上移到 `sources` 之后；`synthesizePlan` 收回 `void`。
- `SynthesisPromptBuilder.Context` 的 `businessOutput` 失去唯一消费者，随之下线。
- 测试：`QaSynthesisFlowTest` 增加「落库早于 `done`」与「零命中但有业务产物仍短路」两条断言。
