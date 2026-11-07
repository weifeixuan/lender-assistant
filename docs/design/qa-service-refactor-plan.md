# /api/qa 接口实现重构方案 · 拆类 + 编排瘦身

> **文档状态**：待实施（已按 2026-11-07 审查意见修订，见「附：审查记录」）。
> **基线**：HEAD `f0863b1`，`QaServiceImpl` **707 行**。
> **给后续 Agent 的交接说明**：本方案是 `/api/qa` 问答链路（`QaServiceImpl`）重构的执行依据。实施前请先读 `docs/agents/coding-standards.md` 与 `GLOSSARY.md`。本文**不新增任何包、也不新增任何工具类**：`RoundStateServiceImpl`、`QaEventEmitter` 落既有 `rag/service/impl`，`common/util` 保持原样；仅需在 §7 步骤 1 补一条命名例外。
> **硬约束（违反即方案不合格）**：行为与契约完全等价——不得改接口路径/方法名、入参出参字段、错误码、SSE 事件协议、事件字段、`done` 口径、事务与副作用顺序、落库字段与序列化形态、Prompt 文本。每步一个 commit，`mvn -q clean test` 全绿才进下一步。任何"可能改变对外行为"的改动一律停下回报，不擅自继续。

---

## 一、背景与现状

`/api/qa` 是 RAG 知识库的多轮问答接口（SSE 流式返回）。入口 `QaController.qa()` 只做入参校验后透传 `QaService.answer()`，真正的实现集中在 `QaServiceImpl`（当前 **707 行**）。

该实现类同时承担三个互不相关的职责：

| 职责 | 现状落点 | 变化原因 |
|---|---|---|
| ① 问答编排 | `streamAnswer` + `routeRound`/`runPlanRound`/`synthesizePlan`/`streamModelAnswer` | 业务流怎么走 |
| ② 流式状态机 + 终态落库 | `RoundStream` 内部类（[L541–683](file:///e:/project/lender-assistant/src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java#L541-L683)，**143 行**） | 一轮生成期间状态怎么管理 |
| ③ SSE 事件发射协议 | `send`/`pushSources`/`emitIrrelevant`/`emitNoRecall`/`handleCircuitOpen`/`handleFailure`/`buildSources`/`toSnapshot`（共 8 个方法，约 97 行） | SSE 协议长什么样 |

本次重构只做结构搬运与命名显式化，**不优化业务逻辑**。目标是把 ②③ 拆出去，让 `QaServiceImpl` 变成顺序可读的编排门面。按实测算，目标行数为 **约 470–480 行（估算区间，非验收承诺）**：

```
707 − 143（RoundStream） − 97（8 个事件方法） + 约 8（委托字段与调用） ≈ 475
```

**明确不做**：不把 `QaServiceImpl` 压到 300 行；不追求"嵌套深度 6 → 3"这类不可复现的指标（实测口径见 §5）。

### 已完成的先行动作

| 项 | 状态 | 说明 |
|---|---|---|
| Prompt 常量收敛到 `QaPrompts` | ✅ **已完成**（commit `f0863b1`） | `systemPrompt()` 方法体、`SYNTHESIS_SYSTEM_PROMPT`、`HISTORY_ANSWER_CONSTRAINT` 已原样迁入 `common/constant/QaPrompts.java`，`QaServiceImpl` 只剩 `QaPrompts.KNOWLEDGE_SYSTEM_PROMPT` / `QaPrompts.SYNTHESIS_SYSTEM_PROMPT` 两处引用。**本方案不再涉及此项。** |

---

## 二、契约清单（等价验收基线，实施全程不得破坏）

### 2.1 接口与校验

| 契约项 | 现状值（必须保持不变） |
|---|---|
| 接口标识 | `POST /api/qa`（`produces=text/event-stream`，`@RawResponse` 跳过统一包装）；`QaService.answer(String question, int topK, String conversationId, String userId) -> SseEmitter` |
| `question` 空校验 | `QaController` 抛 `IllegalArgumentException` → `GlobalExceptionHandler` 映射 **400 PARAM_INVALID**（当前实现，非 `BizException`） |
| `conversationId` 格式校验 | 非 UUID → `BizException(ErrorCode.PARAM_INVALID, "conversationId 必须是 UUID")` → **400** |
| 会话归属校验 | `validateOwnership` 失败 → `BizException(CONVERSATION_NOT_FOUND)` → **404**（注意：这是归属校验，不是格式校验） |
| 并发占锁失败 | `BizException(CONVERSATION_BUSY)` → **409** |

### 2.2 SSE 事件协议（三分支，逐事件固定）

| 路由 | 事件序列 |
|---|---|
| mock（`chatProvider` 为 null 或 `mock`） | `meta → sources → delta… → done`（无 `route`/`plan`/`step`） |
| IRRELEVANT | `meta → route → sources`（空数组）`→ delta`（固定提示）`→ done`（`provider=rule`，`sourceCount=0`），**不检索、不生成** |
| KNOWLEDGE（有命中） | `meta → route → sources → delta… → done` |
| KNOWLEDGE（零命中） | `meta → route → sources`（空数组）`→ delta`（引导文案）`→ done` |
| PLAN | `meta → route → plan → step* → sources`（并集）`→ delta… → done` |

- `meta`：`{conversationId, roundId, userMessageId, assistantMessageId}`。
- `sources`：`QaSourceResponse` 数组，未召回时**仍发空数组**；字段顺序 `idx, docTitle, sectionPath, chunkId, content, vectorDistance, keywordHits, bothChannels, rrfScore`（`idx` 从 1 起）。
- `done`：`{provider, elapsedMillis, sourceCount}`，三种口径：

| 分支 | `provider` | `sourceCount` | 落库 `provider` |
|---|---|---|---|
| KNOWLEDGE / PLAN | `generator.name()` | `hits.size()`（PLAN 为多步并集条数） | `generator.name()` |
| KNOWLEDGE 零命中（`emitNoRecall`） | `generator.name()` | `0` | `generator.name()` |
| IRRELEVANT（`emitIrrelevant`） | `MessageConstants.RULE_PROVIDER`（`rule`） | `0` | `rule` |

- `error`：通用异常推 `"问答失败：" + e.getMessage()`，落库 `error` 同为该串；熔断推固定长文案（"模型服务熔断中（连续失败已触发保护）…"，见 [L486–487](file:///e:/project/lender-assistant/src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java#L486-L488)），落库 `error` 为 `"模型服务熔断中"`。
- **顺序冻结**：
  - 正常路径：`stream.done()`（落库 DONE）**先于** `emitter.complete()`；
  - `emitNoRecall`：`send(done)` **先于** `stream.done()`（[L470–474](file:///e:/project/lender-assistant/src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java#L470-L475)），不得"规范化"；
  - `emitIrrelevant`：`stream.provider(rule)` → `stream.done()` → `send(done)` → `complete()`；
  - 失败路径：先 `send(error)` 再 `stream.failed(...)`；仅 `send` 成功才 `completeWithError(e)`。

### 2.3 副作用与状态

| 契约项 | 现状值 |
|---|---|
| 请求线程 | 占锁 → `beginRound` 落用户消息 → 提交 `streamExecutor` → 立即返回 emitter；提交失败走 `BizException(500)` 并落一条 FAILED 助手消息（provider 为 null） |
| 工作线程 | `meta` → 历史/预算组装 → 路由/计划（非 mock）→ 检索 → `sources` → 生成/合成/无召回 → `done` → `complete` |
| 会话锁 | `ConversationLockRegistry` 在 `answer()` 的 finally 释放；**落库 `completeRound` 发生在锁内**（`stream.done()` 先于 finally），这是现状，本方案不调整顺序 |
| 终态幂等 | `RoundStream.finished` 原子 CAS；`persist` 中 **CAS 在锁外、快照读取在锁内、`completeRound` 由 CAS 赢家执行一次**；各 setter **不检查** `finished` |
| 落库字段 | `content`（终态正文）、`sources`（推送时的快照）、`provider`、`status`、`error`、`route`（JSONB，mock 为 null）、`execution_plan`（JSONB，非 PLAN 为 null） |
| 序列化 | `QaMetaResponse` / `QaSourceResponse` / `QaDoneResponse` 字段名与顺序不变；`QaSourceSnapshotDTO` 与 `QaSourceResponse` 同构 |

---

## 三、拆类方案（核心）

### 3.1 决策汇总

| 决策点 | 结论 | 落点 |
|---|---|---|
| `RoundStream` 内部类 | 提升为顶层类，**改名 `RoundStateServiceImpl`** | `rag/service/impl/RoundStateServiceImpl.java`（**不再放 `rag/domain`**，理由见 §3.4） |
| SSE 事件发送（含私有 `send`） | **新建 `QaEventEmitter`**，迁入 8 个方法（含 `send`）；`send` 为**包级私有**，`QaServiceImpl` 原地 7 处改调 `events.send(...)` | `rag/service/impl/QaEventEmitter.java` |
| `common/util` | **不新增任何类**；不引入 `SseEmitterUtils` | 不动 |
| `delta` 两处去重 | 收成 `QaServiceImpl` 私有 `sendDeltaToken(emitter, token, state)`，**不进** `QaEventEmitter` | `rag/service/impl/QaServiceImpl.java` |
| `QaServiceImpl` | 瘦身为顺序可读的**问答编排** | `rag/service/impl/QaServiceImpl.java` |
| `QaService` 接口 | **保留不动** | `rag/service/QaService.java` |
| 测试 | 随代码规范同步调整，不新增用例（建议单独补契约测试，见 §6.3） | 见 §6 |

### 3.2 包职责边界

本次**不新增任何包、也不新增任何工具类**：`RoundStateServiceImpl`、`QaEventEmitter` 落既有 `rag/service/impl`；`common/util` 保持原样（不加 `SseEmitterUtils`）。

**`rag/service/impl`** —— 问答链路的应用层实现，允许依赖 `conversation` 的 service/model（`QaServiceImpl` 现状即如此，方向一致）。

> `QaEventEmitter` 与 `RoundStateServiceImpl` 归此处：前者绑定 `/api/qa` 的事件语义（IRRELEVANT/无召回/熔断文案都是业务约定），后者做终态落库（应用层职责）。两者都不得反向依赖 `QaServiceImpl`。

**废弃的旧定义**：原稿中的 `rag/domain` 包及「`RoundStream` 是领域核心模型」的定位作废——一个直接 `completeRound` 落库的类不是领域模型，放 `rag/domain` 会造成领域层直接依赖会话服务；原稿的 `common/sse` 包、以及后续的 `common/util/SseEmitterUtils` 均作废：`send` 只有一个调用族，抽独立工具类只会增加跨文件阅读成本，并被迫把轮次状态作为参数穿透。

### 3.3 实施守则

1. **命名**：方法名用动作、字段名用名词；禁止字段名与类名同名（`ExecutionPlanDTO ExecutionPlanDTO` 必须改）。
2. **注释**：只保留「为什么」，删除复述代码的注释；但 §2.2「顺序冻结」相关的注释必须保留并加粗说明不可改。
3. **依赖方向**：`QaEventEmitter`、`RoundStateServiceImpl` 不得反向依赖 `QaServiceImpl`。本方案不新增工具类，`common/util` 保持原样。
4. **只搬不改**：`persist`、`emitNoRecall`、`emitIrrelevant`、`done` 事件构造按行搬运，**不做任何"顺手优化"**。发现契约风险立即停。

### 3.4 新类一：`RoundStateServiceImpl`（`rag/service/impl/`，职责②）

把 `QaServiceImpl.RoundStream` 整体提升为顶层类，改名 `RoundStateServiceImpl`，职责不变。

- **构造依赖**：`ConversationRoundDTO round`、`long startedAt`、`String providerName`、`ConversationService conversationService`、`ObjectMapper objectMapper`。
- **方法清单不变**：`append` / `sources` / `route` / `ExecutionPlanDTO`→`recordPlan` / `contextTruncated` / `done` / `isDisconnected` / `cancellation` / `disconnected` / `timeout` / `failed` / `stopped` / `elapsedMillis` / `provider`。
- **命名修正**：字段 `ExecutionPlanDTO ExecutionPlanDTO` → `executionPlan`；setter 同名改为 `recordPlan(plan, result)`。

#### 设计定论：`ConversationService` 依赖**不抽出**，终态落库留在本类

曾考虑的备选：把 `completeRound` 挪到编排层，`RoundState` 只暴露不可变快照。**否决**，理由：

1. **抽不干净**：即使移除 `ConversationService`，本类仍依赖 `conversation.model.dto` 的 `ConversationRoundDTO`、`QaSourceSnapshotDTO`、`AssistantMessageCompletionDTO`（全是落库载荷字段）。"不依赖 conversation 域"本就无法成立，抽出只是把 service 依赖降级成 model 依赖，问题是名义上的。
2. **拆散原子契约**：`finished` CAS + 锁内快照 + 一次 `completeRound` 三者必须同处一处，才能保证「先到终态者决定内容、且只写一次」。拆开后必须靠「CAS 赢家返回 `Optional<快照>`，非空就必须落库」的隐式约定，`onTimeout`/`onError`/`onCompletion` 三个回调还要绕道编排层取快照，漏写/重写风险上升。
3. **未来扩展反向**：终态落库点以后还会挂消息内容后处理、统计埋点、事件发布、失败重试。放编排层，这些依赖会全部重新注入 `QaServiceImpl`，让它从 ~475 行长回 520+，拆出去的职责等于搬回来；留在本类，则新增协作者只改本类构造参数，编排层签名不变。
4. **层位修正**：做 DB 写入的不是领域模型，属应用层实现，故放 `rag/service/impl` 才是正解——这也是本方案不新增 `rag/domain` 的原因。

**代价（明确接受）**：

- `rag/service/impl` 多一个按轮 `new` 的协作类（约 143 行），与 `QaServiceImpl` 同包、同依赖方向；
- 它不是 Spring Bean，**不建 `RoundStateService` 接口**（接口在本场景只能是空壳，且无法由 Spring 装配）；`ServiceImpl` 后缀沿用 §2 的命名习惯，含义是"应用层实现类"，步骤 1 把这条命名例外写进规范；
- 未来若终态副作用继续膨胀，在 `RoundStateServiceImpl` 内拆私有协作者或加构造参数，**不得**把副作用反向塞回 `QaServiceImpl`。

### 3.5 新类二：`QaEventEmitter`（`rag/service/impl/`，职责③）

原稿曾把纯协议发送抽成 `common/util/SseEmitterUtils`。**否决**：`send` 只有一个调用族（本类 5 个事件方法 + `QaServiceImpl` 原地 7 处），方法体又必须读写 `RoundStateServiceImpl`；抽成独立工具类只增加一层间接与跨文件阅读，并被迫把轮次状态作为参数穿透。`common/util` 不加任何新类。

#### 3.5.1 方法清单（迁入 8 个方法）

| 新方法 | 原方法 | 语义 |
|---|---|---|
| `boolean send(emitter, event, data, state)`（**包级私有**） | `QaServiceImpl.send` | `state.isDisconnected()` 为真直接 false；`emitter.send(SseEmitter.event().name(event).data(data))`；异常 DEBUG 留痕 → `state.disconnected()` → false |
| `boolean sources(emitter, hits, state)` | `pushSources` | 召回 → 快照落 state + `sources` 事件（空命中仍发空数组） |
| `void irrelevant(emitter, reply, state)` | `emitIrrelevant` | 固定提示，`provider=rule`、`sourceCount=0` |
| `void noRecall(emitter, provider, state)` | `emitNoRecall` | 引导文案，保持「先 send(done) 后 stream.done()」 |
| `void circuitOpen(emitter, e, state)` | `handleCircuitOpen` | 熔断文案 + FAILED |
| `void failure(emitter, question, e, state)` | `handleFailure` | `"问答失败：" + msg` + FAILED |
| `static buildSources(hits)` / `static toSnapshot(source)` | 同名静态方法 | 纯转换，与原来一致 |

- **可见性定论**：`send` 为**包级私有**（无修饰符）。它必须被同包的 `QaServiceImpl` 在原地 7 处调用（`meta` / 正常 `done` / `route` / `plan` / `step` / 两处 `delta`）；类外不可见，不构成公共 API。
- **为什么不做成独立工具类**：方法体不是纯函数——开头读 `state.isDisconnected()`、失败后写 `state.disconnected()`；做成静态方法就要把 `state` 穿透进去，做成无状态工具则必须把这两步拆回调用方（7 处重复状态判定），两种都不划算。
- **依赖**：`QaEventEmitter` 依赖 `RoundStateServiceImpl`、rag 响应模型、conversation 快照 DTO —— 都在 `rag/service/impl` 层，符合 §3.2。
- **`QaServiceImpl` 持有**：`private final QaEventEmitter events = new QaEventEmitter();`（无状态，构造签名不变，测试不受影响）。

#### 3.5.2 `delta` 两处去重（留 `QaServiceImpl`）

`streamModelAnswer` 与 `streamSynthesis` 的 token 回调完全同构（`isDisconnected` → `append` → `send(delta)`）。属「第 2 次出现」，按 §3.2 可抽可不抽；本方案选择抽取，但**落点必须是 `QaServiceImpl` 自身**：

`private void sendDeltaToken(SseEmitter emitter, String token, RoundStateServiceImpl state)`

- **留在同一文件**：`append` 是「正文进落库」的状态写，`send` 是协议出口；顺序语义（先 `append` 后 `send`）必须就地可见。放进 `QaEventEmitter` 会让「一条 token 如何进入落库正文」跨两个文件，读者无法一眼确认。
- **不进 `QaEventEmitter`**：它不是业务事件（不决定终态、不改 provider、不落库收尾），而是生成回调的推送原语，由编排层直接复用。
- **冻结**：方法体顺序必须是 `isDisconnected` → `append` → `send`，不得调换；`send` 返回值仍忽略（与现状一致）。

#### 3.5.3 为什么 `emitIrrelevant` / `emitNoRecall` 不下沉（逐方法判定）

> 判定标准只有一条：**把业务名词全部删掉后，这段代码是否仍然成立**。`send` 成立；其余 5 个方法都不成立。

| 方法 | 读写的轮次状态 | 依赖的业务对象 | 内嵌业务判断 | 顺序约束 | 是否进独立工具类 |
|---|---|---|---|---|---|
| `send(emitter,event,data,state)` | 读 `isDisconnected`；写 `disconnected()`→`cancellation.cancel()`+`persist(STOPPED)` | 无 | 无（失败即视为断开） | 先判断开，失败才置位 | **否**（留 `QaEventEmitter` 包级私有） |
| `sources(emitter,hits,state)` | 写 `state.sources(snapshot)` | `SearchItemResponse`、`QaSourceResponse`、`QaSourceSnapshotDTO` | `idx` 从 1 起；空命中仍发空数组 | 先写快照后发事件 | **否** |
| `irrelevant(emitter,reply,state)` | 写 `sources([])`、`append(提示)`、`provider(rule)`、`done()` | `irrelevantReply`（`@Value` 配置文案）、`MessageConstants.RULE_PROVIDER`、`QaDoneResponse` | `provider=rule`、`sourceCount=0`、正文要 `append` 才能落库 | **先 `stream.done()` 落库，后发 `done` 事件** | **否** |
| `noRecall(emitter,provider,state)` | 写 `append(引导文案)`、`done()` | 引导文案字面量、`generator.name()`、`QaDoneResponse` | `provider=当前生成器`、`sourceCount=0` | **先发 `done` 事件，后 `stream.done()` 落库** | **否** |
| `circuitOpen(emitter,e,state)` | 写 `failed(...)`→`persist(FAILED)` | resilience4j `CallNotPermittedException`、熔断降级文案 | 熔断文案、`sent` 为真才 `completeWithError` | 先发 `error` 再 `failed` | **否** |
| `failure(emitter,question,e,state)` | 写 `failed(...)`→`persist(FAILED)` | 异常链、`question` 日志上下文 | `"问答失败：" + msg`、`sent` 为真才 `completeWithError` | 先发 `error` 再 `failed` | **否** |

三条决定性理由：

1. **规范边界**：若再抽独立工具类，`common/util` 必须 import `rag.model.response` / `conversation.model.dto`，并依赖 `RoundStateServiceImpl`，直接违反 §1 表 L74「`common/util/` 只放无状态静态方法，禁止持有 Spring Bean、实例状态」；也违反 §3.1「一个方法只做一件事」——`emitXxx` 同时做状态写入、业务口径与协议发送三件事。
2. **参数化后仍不可复用**：要下沉 `noRecall`，入参需同时包含 `state`、`provider`、`guidance`、`sourceCount`、以及「先落库还是先发事件」；参数化到这个程度，它已经不是「SSE 工具」，而是「无召回分支」换了个包名。
3. **会逼出业务分支**：`irrelevant` 是「先 `stream.done()` 再发事件」，`noRecall` 是「先发事件再 `stream.done()`」——两者顺序相反。要在同一个 utils 方法里兼容，只能加 `if (persistFirst)`；**工具类里出现业务分支，等于自证分层错误**。

**按名字切 vs 按依赖切**：用户直觉是「凡是通过 SseEmitter 发东西的都进独立工具」；正确切法是按「是否只碰传输层」。`emitXxx` 是轮次生命周期节点（决定终态、落库、文案），SSE 只是它的出口副作用，因此留 `QaEventEmitter`。本方案不再新增任何工具类：`send` 的协议部分只有一行 `event().name().data()`，单独成类的收益不足以抵消跨文件与状态穿透成本。

### 3.6 `QaServiceImpl` 退化为编排门面（职责①）

只保留：`answer()`（占锁/落库/线程调度）、`streamAnswer()` 骨架 + `routeRound`/`runPlanRound`/`synthesizePlan`/`streamModelAnswer` + 私有 `sendDeltaToken`，以及两个 record（`PlanRoundOutcome`、`RoundRequest`）。

骨架保持一条直线（**注意：mock 守卫维持 `if (!isMockMode())` 的现有形态，不改成提前 return，理由见 §5**）：

```text
meta 首事件 → 历史/预算组装 → 非 mock：路由（IRRELEVANT 提前 return；PLAN 执行）
→ 检索命中（并集或单次）→ sources → 三路收尾 → done → complete
```

---

## 四、抽象分级审查

| 抽象 | 归类 | 判据（证据） | 处置 |
|---|---|---|---|
| `QaService` 接口 | C（保持） | 接口+impl 是项目既定分层 | 不动 |
| `RoundStream` 内部类 | B（显式化） | 职责真实（状态机+幂等落库），但 **143 行**塞在编排类里；字段/setter 命名不表意 | 提升顶层 → `RoundStateServiceImpl`（放 `rag/service/impl`） |
| SSE 事件发射（8 个私有方法） | B（显式化） | 协议与业务语义混在一起；`send` 依赖轮次状态，无法独立成无状态工具 | 拆为 `QaEventEmitter`（迁入 8 个方法，含**包级私有** `send`）；`delta` 两处去重为 `QaServiceImpl.sendDeltaToken` |
| `RoundRequest` record | C（保持） | 收敛编排方法入参（>4） | 保持 |
| `PlanRoundOutcome` record | C（保持） | PLAN 并集命中 + 预算余量载体 | 保持 |
| `isMockMode()` | C（保持） | **只判 `chatProvider`（null 或 `mock`）**；`routingService == null` 是 `routeRound` 内的独立守卫，两者不是同一防线，也不合并 | 保持 |
| `AnswerGeneratorStrategy` | C（保持） | 2 个真实实现（mock/openai），`@ConditionalOnProperty` 切换 | 不动 |

---

## 五、控制流审查（修正原稿的两处「压平」）

**口径说明**：嵌套深度按「方法体内的代码块（含 lambda 体）从 1 计」实测。`streamAnswer` 最深为 5 层（方法体 → `try` → `if (!isMockMode())` → `if (PLAN)` → `if (!send)`）；`streamSynthesis` 最深 5 层（方法体 → `executor.execute` lambda → `token` lambda → `if (isDisconnected)` → 调用）。本方案**不承诺**降到 3 层。

### 第 1 处：`if (!isMockMode())` —— **保留嵌套，不做提前 return**

原因：mock 分支不是"做完就结束"，它必须继续走检索 → `sources` → 三路收尾 → `done` → `complete`（[L276–300](file:///e:/project/lender-assistant/src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java#L276-L300)）。写成 `if (isMockMode()) { …; return; }` 只有两种落法：

- 把 24 行尾链复制进 mock 分支 → 尾链两份、代码净增约 8 行，且后续改 `done`/`sourceCount` 要同步两处；
- 新增公共尾段方法 `finishRound(...)` 供两处调用 → 净减约 3 行，但引入一个新方法（本轮明确不引入新抽象）。

两种都不满足"净收益 + 不复制尾链"，故**写死为保留现状**。IRRELEVANT 已经提前 return（[L265–268](file:///e:/project/lender-assistant/src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java#L265-L269)），不需要再动。

### 第 2 处：三路收尾 —— **做守卫化，但必须带 `planOutcome == null`**

唯一允许的形态（写死）：

```java
if (planOutcome == null && hits.isEmpty()) {
    events.noRecall(emitter, generator.name(), stream);
    return;
}
if (planOutcome != null && !synthesizePlan(emitter, request, planOutcome, hits)) {
    return;
}
if (planOutcome == null) {
    streamModelAnswer(emitter, question, history, hits, stream);
}
```

**顺序要求（违反即行为变化）**：

1. 零命中守卫必须带 `planOutcome == null`。PLAN 轮零命中但有业务产物时**仍要合成**（[L375–378](file:///e:/project/lender-assistant/src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java#L375-L379)），把 `hits.isEmpty()` 提到 PLAN 分支之前，会把这类轮次从「合成 delta」变成「无召回提示」，事件序列与 `sourceCount` 同时改变。
2. `synthesizePlan` 必须在任何"零命中即结束"的结论之前调用。
3. `hits.size()`（`sourceCount`）与 `sources` 数组仍来自同一 `hits`，三路中不得重新计算。

**收益**：净减约 1–2 行，纯分支形态调整；不宣称"净减 15 行"。

---

## 六、测试影响

### 6.1 现状事实（已核实）

- **只有 3 个测试文件直接 `new QaServiceImpl(...)`**：`QaServiceTest`、`QaRoutingFlowTest`、`QaSynthesisFlowTest`。
- `QaControllerTest` **不构造 `QaServiceImpl`**：它用 `mock(QaService)` + standalone MockMvc，只覆盖 HTTP 层 400/404/409 与参数透传。
- `QaServiceTest` 覆盖：meta 首事件、meta 发送失败→STOPPED、成功落库、零命中引导、模型异常→FAILED、断连→STOPPED 且取消上游、SSE 超时→STOPPED、线程池拒绝→500+FAILED、锁的 409/404/释放/不同会话互不阻塞。
- `QaRoutingFlowTest` 覆盖：IRRELEVANT 序列、KNOWLEDGE 多一个 `route`、路由异常降级、mock 跳过路由。
- `QaSynthesisFlowTest` 覆盖：PLAN 只调一次生成、零命中且无业务产物→无召回、剩余预算耗尽、合成异常、合成超时、计划弃用。
- **截断用例不在 `QaSynthesisFlowTest`**，在 `SynthesisPromptBuilderTest`。
- **当前缺失**：① 无任何 `done` 字段/`sourceCount` 断言（全仓库测试中 `sourceCount` 0 命中）；② 无显式「终态只落库一次」断言（只被终态状态间接覆盖）；③ `QaControllerTest` 无 `question` 空校验用例。

### 6.2 拆类的测试影响

1. `QaServiceImpl` 构造签名**不变**（`QaEventEmitter` 内部 `new`，`RoundStateServiceImpl` 每轮 `new`），现有 `new QaServiceImpl(...)` 不变；字段注入的测试配置（`chatProvider`/`irrelevantReply` 等）也不变。
2. 现有测试均不直接触碰 `RoundStream`，只通过 emitter 事件流与 `completeRound` 断言；提升为顶层类不影响断言入口。
3. `RecordingEmitter` 重写的是 `SseEmitter.send(SseEventBuilder)`，`QaEventEmitter.send` 仍调用同一方法 → 事件采集行为不变。
4. **`delta` 去重的测试影响**：`sendDeltaToken` 仍按 `isDisconnected → append → send` 执行，`RecordingEmitter` 采集的事件序列与落库正文顺序不变；`QaEventEmitter` 内部 `new`，测试接缝仍是 `SseEmitter`，无需新增 mock 配置。

### 6.3 建议：拆类前补契约测试（本方案不强制，但建议）

由于 6.1 的 ①②③ 缺失，建议先单独提交一次契约测试（`QaServiceImplContractTest` 或按 §9.8 命名的 `<被测对象>ContractTest`，同包放置），至少断言：

- 三分支 `done` 的 `provider` 与 `sourceCount`；
- `meta` 四字段、`sources` 空数组形态、`source` 九字段；
- `question` 空 → 400 + `msg`；
- 同一轮 `completeRound` 只调用一次（断连/超时各一条）。

若不做，步骤 5 必须按 §9 做**人工 diff**，不能声称"由现有用例保证"。

---

## 七、分阶段执行计划（原子步骤，每步独立 commit，不 push）

> 每步完成定义：`mvn -q clean compile` 通过 → `mvn -q clean test` 全绿 → 独立 commit。

### 前置 P0｜处置工作区与方案文档

- 当前工作区 `docs/agents/coding-standards.md` 有一处**未提交修改**（§0.2 删括注，属票外改动）。执行步骤 1 前必须先处理：单独提交或还原，**不得混入步骤 1 的 commit**。
- 本方案文档当前未跟踪。建议先单独 `docs(design): 提交 /api/qa 重构方案` 入库，保证实施过程可回溯（建议项，不阻塞）。

### 步骤 1｜补命名例外（纯文档，0 代码，不改包树）

- 改动点：`docs/agents/coding-standards.md`
  - **§0.1 包树不动**：本方案不新增任何包、不新增任何类（`common/util` 保持原样）；
  - 只补一条命名例外：按轮 `new` 的应用层协作类（非 Spring Bean）同样以 `ServiceImpl` 结尾，可不配接口；
  - 不改 §1 `common/util/` 行，不为「SSE 方法下沉」新增任何规范条款。
- 验证：无。回滚：revert。风险：低。

### 步骤 2｜提升 `RoundStream` → `RoundStateServiceImpl`（先做）

- 改动点：新建 `rag/service/impl/RoundStateServiceImpl.java`，逐行迁入 `RoundStream`；构造依赖改为参数传入（`round`/`startedAt`/`providerName`/`ConversationService`/`ObjectMapper`）；`ExecutionPlanDTO` 字段改 `executionPlan`、setter 改 `recordPlan`；`QaServiceImpl` 每轮 `new` 该类，`providerName` 传 `generator.name()`。
- **冻结**：`persist` 的「CAS 在锁外 → `synchronized` 内读快照 → 调 `completeRound`」逐行保持；各 setter 仍不检查 `finished`；不合并 CAS 与锁。
- 验证：`QaServiceTest`（断连/超时/取消/拒绝/锁）、`QaRoutingFlowTest`、`QaSynthesisFlowTest`（预算耗尽/合成超时/合成异常/计划弃用，外加 `SynthesisPromptBuilderTest` 的截断用例）全绿。
- 回滚：revert。风险：中（宿主引用 `QaServiceImpl.this.generator.name()` 需改为构造传值，grep 确认无 `QaServiceImpl.this` 残留）。

### 步骤 3｜提取 `QaEventEmitter`（业务事件 + 包级私有 `send`）+ `delta` 去重

- 改动点：新建 `rag/service/impl/QaEventEmitter`（`boolean send(emitter, event, data, state)` 为**包级私有**，完整保留 `isDisconnected` → 发送 → catch → DEBUG → `disconnected()` → false；5 个业务事件方法 + `buildSources`/`toSnapshot`）；`QaServiceImpl` 的 5 处改调 `events.xxx(...)`，原地 7 处 `send` 改 `events.send(...)`；新增私有 `void sendDeltaToken(emitter, token, state)`，替换 `streamModelAnswer`/`streamSynthesis` 两处同构回调。
- **冻结**：`noRecall` 的「先 `send(done)` 后 `stream.done()`」；`irrelevant` 的「先 `stream.done()` 后 `send(done)`」；`provider=rule` / `sourceCount=0`；`failure`/`circuitOpen` 的事件文案与落库 `error` 串；状态判定顺序（先 `isDisconnected`，失败才 `disconnected`）；`sendDeltaToken` 的「先 `append` 后 `send`」。**不新增 `SseEmitterUtils` 或任何工具类**。
- 验证：三个流程测试的事件序列断言全绿（`RecordingEmitter` 采集不受影响）；grep 确认无 `SseEmitterUtils`、`common/util` 无新增文件。
- 回滚：revert。风险：中（`sources` 的 `toSnapshot` 涉及 conversation DTO，必须留在 `QaEventEmitter`；`send` 改包级私有后，类外仍不可见）。

### 步骤 4｜`streamAnswer` 收尾（仅第 2 处守卫化）

- 改动点：只按 §5 第 2 处形态改写三路分支；**第 1 处不动**。
- 验证：`mvn -q clean test` 全绿；`done.sourceCount` 与事件序列人工核对（三路由）。
- 回滚：revert。风险：中（守卫顺序写错会改变 PLAN 零命中行为，必须按 §5 顺序要求实现）。

### 步骤 5｜全量回归 + 契约人工核对

- 改动点：无代码；只跑全量测试并逐项核对 §2 与 §9。
- 验证：用例数不下降（当前基线 **248**）；`done` 三分支口径、`noRecall` 顺序、`persist` 竞态窗口、落库四字段、错误文案逐项人工 diff。
- 回滚：revert（若仅文档/无改动则无需）。

> **顺序说明**：必须先步骤 2 再步骤 3。反之 `QaEventEmitter` 会依赖 `QaServiceImpl` 的内部类，形成反向依赖。

---

## 八、量化对比（实测 / 估算）

| 指标 | 重构前（实测） | 重构后（估算，非承诺） |
|---|---|---|
| `QaServiceImpl` 行数 | 707 | 约 470–480 |
| `RoundStream` 行数 | 143（L541–683） | 0（迁入 `RoundStateServiceImpl`） |
| 类数量（本链路） | 1（+1 内部类） | 3（QaServiceImpl / RoundStateServiceImpl / QaEventEmitter） |
| 新增类数量 | — | **2**（`RoundStateServiceImpl`、`QaEventEmitter`）；不新增 `SseEmitterUtils` |
| 新增包数量 | — | **0**（两类均落既有 `rag/service/impl`） |
| `streamAnswer` 最大嵌套 | 5 层（口径见 §5） | 5 层（步骤 4 不改变最深链） |
| SSE 协议改动位置数 | 散在 8 个私有方法 | 集中在 `QaEventEmitter` 一处（5 事件 + 2 转换 + 包级私有 `send`）；`delta` 去重在 `QaServiceImpl.sendDeltaToken` 一处 |
| 流式状态改动位置数 | 内部类 | `RoundStateServiceImpl` 一处 |

**已删除的不实承诺**：原稿「~300 行」「嵌套 6 → ≤3」「净减 15 行」——均不可由现有代码与改动量推导。

---

## 九、行为与契约等价自检表（标注现有护栏强度）

| 项 | 预期 | 验证方式 | 护栏强度 |
|---|---|---|---|
| 路径与方法名 | 不变 | `QaControllerTest` | 强 |
| `question` 空校验 | 不变（`IllegalArgumentException`→400） | **无现成用例** | **缺失**（建议补，见 §6.3） |
| `conversationId` 格式/归属/占锁 | 不变（400/404/409） | `QaControllerTest` 3 例 | 强（缺 question 空一例） |
| `meta` / `sources` / `done` 字段 | 不变 | **无字段级断言**，事件名有 | **弱**（需人工 diff 或新增契约测试） |
| 三分支 `done.sourceCount` | 见 §2.2 | **无断言** | **缺失**（必须人工核对） |
| 事件序列（三分支） | 见 §2.2 | `QaServiceTest` / `QaRoutingFlowTest` / `QaSynthesisFlowTest` | 强 |
| 错误文案与落库 `error` | 见 §2.2 | 失败/熔断用例 + 人工 diff | 中 |
| 终态只落库一次 | CAS 语义不变 | 无显式"调用一次"断言 | 弱 |
| 锁与顺序 | 锁内 `completeRound` | 409/释放用例 | 中 |
| `noRecall` send→persist 顺序 | 保持 | 事件序列用例 | 中（顺序本身无断言） |
| 落库 `content/sources/route/execution_plan/provider` | 不变 | 落库断言（content/provider/sources 有，route/plan 弱） | 中 |
| Prompt 文本 | 不变（本方案不涉及） | 子串断言（`QaServiceTest`，非逐字） | 弱（本方案不动，风险为零） |
| 序列化兼容 | 不变 | **无快照测试** | **缺失**（人工 diff） |
| 并发/线程池/@PreDestroy | 不变 | 拒绝策略用例 | 强 |

---

## 十、风险清单

| 风险 | 缓解 |
|---|---|
| 内部类提升顶层后宿主 `this` 引用断裂 | 步骤 2 改构造传值；grep `QaServiceImpl.this` 清零 |
| `QaEventEmitter` 反向依赖 `QaServiceImpl` | 先做步骤 2；`QaEventEmitter` 只依赖 `RoundStateServiceImpl` |
| `common/util` 边界被侵蚀 | **本方案不新增任何工具类**；`common/util` 保持原样，`send`/`toSnapshot`/`buildSources` 全部留在 `QaEventEmitter` |
| `QaEventEmitter` 职责扩张（协议+业务+状态收尾） | 只迁入现状 8 个方法，不新增公共 API；`send` 包级私有；`delta` 不进本类 |
| `delta` 去重时顺序写反 | §3.5.2 写死 `isDisconnected → append → send`；改动即停 |
| 拆类时"顺手优化"改变竞态窗口 | §2.3/§3.3 已冻结 `persist` 结构与 `noRecall` 顺序；改动即停 |
| 守卫顺序写错改变 PLAN 零命中行为 | §5 写死 `planOutcome == null && hits.isEmpty()` 顺序 |
| 测试护栏弱，回归"全绿"却语义已变 | 步骤 5 按 §9 人工 diff；建议先补 §6.3 契约测试 |
| 工作区票外改动混提交 | P0 先处置 `coding-standards.md` 未提交修改 |
| 命名例外未进规范 | 步骤 1 先补「按轮 `new` 的应用层协作类可用 `ServiceImpl` 后缀、免接口」 |

---

## 十一、已决问题（不再待确认）

1. **`RoundState` 位置与命名**：`rag/service/impl/RoundStateServiceImpl`，依赖 `ConversationService` 保留在类内，不建接口（定论与理由见 §3.4）。
2. **不新增 `SseEmitterUtils`**：`common/util` 不加任何类；`send` 留在 `QaEventEmitter` 内，可见性为**包级私有**（`boolean send(...)`，无修饰符），完整保留 `isDisconnected` 前置 + 发送 + 失败 `disconnected()`；`QaServiceImpl` 原地 7 处改调 `events.send(...)`。
3. **`delta` 去重位置**：收成 `QaServiceImpl` 私有 `sendDeltaToken(emitter, token, state)`，**不进** `QaEventEmitter`；`meta` / `done` 维持原地不动。
4. **`providerName` 初始值**：由 `QaServiceImpl` 构造 `RoundStateServiceImpl` 时传入 `generator.name()`。
5. **`rag/domain` 包**：不新增。原方案中的该包与「领域核心模型」定位作废。
6. **测试**：不新增用例为默认；§6.3 的契约测试为建议项，若不做则步骤 5 必须人工 diff。

---

## 附：审查记录

| 时间 | 审查者 | 结论 | 备注 |
|---|---|---|---|
| 2026-11-07 | — | 待实施 | 决策已定稿（RoundStateServiceImpl/rag.service.impl、SseEmitterUtils/common.util、测试随规范走） |
| 2026-11-07 | 审查 | 需补充 → **已修订** | 修正：§5 两处"提前 return"（第 1 处改为保留嵌套并写死理由，第 2 处写死守卫条件）；补三分支 `done.sourceCount`；修复 `common/sse` 边界矛盾（拆为纯协议 + 业务事件两类）；修正 §2 `conversationId` 错误码；重写 §6 测试事实（3 个类构造、`QaControllerTest` 不构造、缺失项）；删除 ~300 行/嵌套 6→3/净减 15 行等不实数字；冻结 `persist` 竞态窗口与 `noRecall` 顺序；`RoundState` 改放 `rag/service/impl` 并给出依赖保留的定论 |
| 2026-11-07 | 命名/放置复核 | 可静态化，但须剥离状态 | `send` 含 `isDisconnected()`/`disconnected()` 两处状态动作，不能原样静态化；改为 `common/util/SseEmitterUtils`（静态 `send`）+ `QaEventEmitter` 私有 `send(..., state)`；不新建 `common/sse` 包、不做 Spring Bean；包名按规范 §2.5 用单数 `util`（非 `utils`）；同步 §3.5 / 步骤 3 / §8 / §10 / §11 |
| 2026-11-07 | 下沉范围复核 | **只有 `send` 可下沉** | 逐方法核实 `sources`/`irrelevant`/`noRecall`/`circuitOpen`/`failure` 全部读写轮次状态或依赖 rag/conversation 对象，且 `irrelevant` 与 `noRecall` 的 `done` 顺序相反；下沉会逼出业务分支、违反 §1 L74；新增 §3.5.3 逐方法判定，同步 §3.1/§4/步骤 3/§8/§11 |
| 2026-11-07 | 方案 A 定稿 | **取消 `SseEmitterUtils`** | `send` 的 7 处原地调用无法通过私有方法跨类访问，定夺为 `QaEventEmitter` 内**包级私有** `send`，`QaServiceImpl` 原地 7 处调 `events.send(...)`；`common/util` 不加任何类、新增类数量 = 2；`delta` 去重收为 `QaServiceImpl.sendDeltaToken`；同步 §3.1/§3.2/§3.5/§4/步骤 1/3/§8/§10/§11/附录 |

## 附：变更历史

| 时间 | 变更 |
|---|---|
| 2026-11-07 | 初稿：拆类方案（RoundState + SseEmitterManager），QaPrompts 标注已完成 |
| 2026-11-07 | 补 §3.2 两包职责边界、§3.3 实施守则 |
| 2026-11-07 | 修订版：按审查意见修正全部基线事实与契约清单；`RoundState` 改落 `rag/service/impl/RoundStateServiceImpl`（保留 `ConversationService`）；SSE 拆为 `SseEmitterManager`（纯协议）+ `QaEventEmitter`（业务事件）；删除不可达的量化承诺 |
| 2026-11-07 | 命名/放置定稿：纯协议发送改为**静态工具** `common/util/SseEmitterUtils`（不新建 `common/sse`、不做 Bean、不新增包）；断开状态动作上移 `QaEventEmitter`；§3.5 拆为 3.5.1/3.5.2，步骤 1 改为「补命名例外、不改包树」，步骤 3 与 §8/§10/§11 同步 |
| 2026-11-07 | 下沉范围定稿：`SseEmitterUtils` 仅保留 `send`；`emitIrrelevant`/`emitNoRecall` 等 5 个方法按依赖与状态判定不下沉，理由落 §3.5.3；事件名常量提为「可选补强、本次不做」 |
| 2026-11-07 | 方案 A 定稿：**取消 `SseEmitterUtils`**（`common/util` 不加类、新增类数量 = 2）；`QaEventEmitter.send` 为包级私有，`QaServiceImpl` 原地 7 处改 `events.send(...)`；`delta` 去重收为 `QaServiceImpl.sendDeltaToken`；`meta`/`done` 原地不动 |
