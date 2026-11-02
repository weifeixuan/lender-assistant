# 多轮会话 Spec（后端实现）

Status: ready-for-agent

## Problem Statement

当前问答只能完成孤立的一轮：用户提出问题、看到一次流式回答，关掉页面或刷新后内容全部丢失；用户无法回到之前的问题继续追问，也无法在多个话题之间切换。引用来源只在当次流式过程中出现，历史回答无法回看引用。后端也没有会话归属概念，任何人都能凭 id 读取别人的会话。前端因此只能做一个"一次性输入框"，无法形成主流 AI 产品那样的侧栏会话体验。

## Solution

后端引入"会话"与"消息"两个持久化概念，把每一轮问答显式落库，并让流式接口在多轮上下文中工作：

- 侧栏顶部「新对话」只进入前端草稿态，不创建服务端记录；草稿不占列表。
- 用户发出首问时，后端懒创建会话、生成会话 id 与本轮 id，通过 SSE 首个 `meta` 事件回传，前端据此把草稿转正并插入「最近」分组顶部。
- 后续提问携带 `conversationId`，后端校验归属、追加一轮，并把最近 6 个完整轮次（上限 2000 字符）回放给模型。
- 每轮用户消息与助手消息共享 `roundId`，历史接口返回带 `roundId` 的扁平消息数组，前端据此聚合"问题—回答—引用"。
- 助手消息在正常结束、失败、停止三种终态都落库，历史可完整还原。
- 会话删除为软删；删除入口只在侧栏条目 hover 时出现，二次确认后调用删除接口。
- 所有接口用 `X-User-Id` 做临时的用户隔离，后续可平滑替换为登录态。

## User Stories

1. 作为用户，我希望侧栏顶部固定一个「新对话」入口，这样我可以随时开始新话题。
2. 作为用户，我希望点击「新对话」后右侧出现空白新会话且左侧列表不新增条目，这样草稿不会污染我的会话列表。
3. 作为用户，我希望在草稿态直接输入问题，这样我不需要先做额外的"创建会话"操作。
4. 作为用户，我希望首问发出后会话立刻出现在「最近」分组最前面并高亮，这样我能确认它已被保存。
5. 作为用户，我希望会话标题取首问前 20 个字符，这样我不需要手动命名也能辨认会话。
6. 作为用户，我希望「最近」分组按最近更新时间排序，这样刚聊过的会话总在最上面。
7. 作为用户，我希望刷新或重新打开页面后仍能看到历史会话，这样我的问答不会丢。
8. 作为用户，我希望点击侧栏任一会话就切换到它的历史，这样我可以在多个话题间来回。
9. 作为用户，我希望历史中我的提问在右、助手回答在左，这样对话双方一眼可辨。
10. 作为用户，我希望助手回答下方能展开引用来源，看到文档标题与章节路径，这样我能核对答案依据。
11. 作为用户，我希望追问"那二套呢"时模型知道上一轮在问什么，这样多轮对话是连贯的。
12. 作为用户，我希望回答生成过程中可以点「停止」，这样我不会被不需要的长回答拖住。
13. 作为用户，我希望停止后已生成的内容保留并标记「已停止」，这样我能看到已经得到的部分。
14. 作为用户，我希望回答失败时看到错误提示与「重试」，这样网络或模型抖动时不用重新输入问题。
15. 作为用户，我希望重试用原问题重新生成，且旧失败记录仍保留，这样我不会丢失上下文。
16. 作为用户，我希望悬停会话条目时才出现小删除按钮，这样列表平时保持干净。
17. 作为用户，我希望删除前有二次确认，这样我不会误删会话。
18. 作为用户，我希望草稿态没有任何删除入口，因为它本来就不在列表里。
19. 作为用户，我希望空态是欢迎语加可用输入框，而不是一块空白列表面板，这样我知道下一步该做什么。
20. 作为用户，我希望删除后的会话不再出现在列表、也打不开历史，这样"删除"是可信的。
21. 作为用户，我希望看不到别人的会话，即使我知道对方的会话 id，这样我的数据是隔离的。
22. 作为用户，我希望同一会话同时只能有一个回答在生成，第二次提问得到明确提示，这样历史不会被我自己的并发操作打乱。
23. 作为用户，我希望很长的历史不会无限塞给模型，这样回答速度与成本可控。
24. 作为用户，我希望「项目」分组暂时不出现，这样界面只呈现已经可用的功能。
25. 作为用户，我希望窗口较窄时会话侧栏可以收起，这样对话区仍然可读。

## Implementation Decisions

### 领域与持久化

- 新增 `conversation` 与 `message` 两张表，PostgreSQL 为唯一事实源；建表 SQL 同步写入项目现有的 schema 事实源并保持幂等。
- `conversation`：`id` 自增主键、`conversation_id` 唯一、`user_id`、`title`、`status`（`ACTIVE` / `DELETED`，仅内部使用）、`create_time`、`update_time`；按 `(user_id, status, update_time DESC)` 建索引。
- 会话只在首问受理时创建，库中不存在"新会话"空记录；`title` 取首问正文去首尾空白后的前 20 个 Unicode code point。
- 每次用户消息落库、每轮助手消息落库，都刷新会话 `update_time`。
- `message`：`id` 自增主键、`conversation_id`、`round_id`、`message_id` 唯一、`role`（`USER` / `ASSISTANT`）、`content`、`status`（`DONE` / `FAILED` / `STOPPED`）、`sources` JSONB、`provider`、`elapsed_ms`、`error_msg`、`create_time`、`update_time`。
- 必须存在 `UNIQUE (conversation_id, round_id, role)`：一轮最多一条用户消息、一条助手消息。
- `(conversation_id, id)` 建索引；历史按 `id ASC` 返回，轮次归属只认 `round_id`。
- `sources` 仅助手消息写入，内容为 `QaSourceResponse` 数组快照；用户消息为 `NULL`。未召回时写空数组，不写 `NULL`（避免"未召回"与"用户消息"语义混在一起）。
- 删除为软删：只把 `conversation.status` 置 `DELETED` 并刷新 `update_time`；消息保留。
- 本阶段不物理删除消息，也不做会话恢复接口。

### 轮次关联

- 同一轮用户消息与助手消息共享同一个 `round_id`；`roundId` 在受理提问时生成，同时用于 SSE `meta`。
- 不采用 `reply_to_message_id` 或"两者都要"：`round_id` 足以分组，唯一约束可直接防一轮多答/多问。
- `round_id` 不承担跨会话唯一性，只在本会话内有意义。

### 接口

所有接口（除 SSE 流本身）使用统一包装 `{code, msg, data}`，且必须携带 `X-User-Id`。

- `GET /api/conversations`：无参数、不分页；只返回当前用户 `ACTIVE` 会话，按 `update_time DESC, id DESC`；无会话返回 `200` + 空数组。
- `GET /api/conversations/{conversationId}/messages`：`200` + `{conversationId, messages}`；`messages` 按 `id ASC` 扁平返回，每条都带 `roundId`、`role`、`status`、`sources`、`provider`、`elapsedMillis`、`error`、`createTime`；不按轮分组。不存在 / 已删除 / 非本人一律 `404 CONVERSATION_NOT_FOUND`。
- `DELETE /api/conversations/{conversationId}`：`200` + `{conversationId, deleted: true}`；软删；不存在 / 已删除 / 非本人一律 `404`；重复删除也是 `404`。
- `POST /api/qa`（SSE）：请求体 `{conversationId?, question, topK?}`。
  - `conversationId` 缺省或 `null` 表示首问懒创建；提供时必须是 UUID，且属于当前用户、状态 `ACTIVE`。
  - `question` trim 后不得为空；`topK` 默认 4，≤ 0 视为非法。
  - `400 / 404 / 409` 都在返回 `SseEmitter` 之前同步抛出，因此是普通 JSON 错误体，不是 SSE 流。
- 错误码：沿用统一错误码枚举，新增 `CONVERSATION_NOT_FOUND(404)` 与 `CONVERSATION_BUSY(409)`；`X-User-Id` 缺失/空白、id 格式非法、question 空白、topK 非法都归 `PARAM_INVALID(400)`；未预期异常归 `INTERNAL_ERROR(500)`。

### 首问懒创建与 `meta`

- 前端新建只进入草稿态，不调用接口；首问请求不带 `conversationId`。
- 后端在请求线程内生成 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`，先落会话与用户消息，再返回 emitter。
- 工作线程的第一个事件必须是：

```json
event: meta
data: {"conversationId":"...","roundId":"...","userMessageId":"...","assistantMessageId":"..."}
```

- `meta` 不能并入 `done`：前端需要在首 token 之前拿到 id，完成草稿转正与临时气泡 key 替换。
- 后续事件严格为 `sources → delta* → done`；未召回时 `sources` 为空数组，仍发 `delta`（固定提示文案）与 `done`。
- `done` 只保留现有 `provider`、`elapsedMillis`、`sourceCount` 三字段。

### 消息状态与落库时机

- 用户消息在受理时立即插入，状态 `DONE`。
- 助手消息在生成结束插入：
  - 正常结束 → `DONE`，写入 `sources`、`provider`、`elapsed_ms`。
  - 模型或系统异常 → `FAILED`，保留已生成正文，`error_msg` 非空。
  - 客户端停止或连接断开 → `STOPPED`，保留已生成正文。
- 会话在流式过程中被删除：当前轮允许跑完，但结束时跳过助手消息落库；后续请求 `404`。
- 历史中 `FAILED` / `STOPPED` 助手消息与其用户消息共享 `roundId`，照常展示，但不进入模型上下文。

### 历史装配

- 取当前用户消息之前最近 6 个完整轮次，按时间正序回放。
- 按 `round_id` 分组；每组必须同时存在一条 `USER` 与一条 `ASSISTANT`，且两者都是 `DONE`。缺任一条整轮丢弃，不与相邻轮拼接。
- 总字符上限 2000（只统计历史 Q/A 正文）；超限从最旧一轮开始整轮丢弃。
- 新建会话历史为空。
- 检索 query 使用"最近一条历史用户消息正文 + 换行 + 当前问题"；无可用历史时只用当前问题。不做 LLM 改写。
- 配置项：`rag.qa.history-max-rounds=6`、`rag.qa.history-max-chars=2000`。

### 生成策略签名

`AnswerGeneratorStrategy` 从"当前问题 + 检索命中"扩展为"系统提示 + 有序历史 + 当前问题 + 检索命中 + 增量回调"。历史以有序 `ChatTurn(role, content)` 传入；OpenAI 兼容实现按顺序映射为对话消息，再追加当前用户消息；mock 实现忽略历史但签名一致。

### SSE 生命周期与客户端断开

- 请求线程只做校验、落库、创建 emitter、提交任务；检索与生成全部在独立流式线程池执行，控制器立即返回 emitter。
- 注册 `onTimeout` / `onError` / `onCompletion`，统一置位"客户端已断开"标志；工作线程每次发送前检查，发送失败也视为断开并抛出控制流异常。
- 发送失败或客户端断开：停止推送，助手消息按 `STOPPED` 落库，不再尝试发送 `error`。
- 模型异常：发送 `error`（纯文本）后正常结束流，助手消息按 `FAILED` 落库。
- emitter 超时沿用现有流式超时配置；超时按客户端断开处理。
- 线程池拒绝：请求线程捕获拒绝异常，落一条 `FAILED` 助手消息（保留空正文、错误说明），释放会话锁，再抛 `INTERNAL_ERROR`，返回普通 500 JSON。

### 事务边界与并发

- 新建会话 + 用户消息：一个事务。
- 既有会话追加用户消息 + 刷新 `update_time`：一个事务。
- 助手终态落库 + 刷新 `update_time`：独立事务；不得把模型调用或 SSE 推送包进数据库事务。
- 软删：一个事务。
- 历史读取不加事务。
- 并发锁为进程内 `conversationId` 集合：请求线程在落用户消息前获取，工作线程 `finally` 释放；线程池拒绝或提交异常路径也必须释放。
- 锁只保证单实例内同会话串行；多实例部署下 `409` 不成立，这是本阶段已知限制。

### 用户标识

- 新增 Web 层拦截器，对 `/api/conversations/**` 与 `/api/qa` 校验 `X-User-Id`：去空白后非空且长度受控；合法则写入 request attribute，非法抛 `PARAM_INVALID`。
- Controller 用 `@RequestAttribute("userId")` 取值，不用 ThreadLocal，避免异步线程拿不到或串值。

### 客户端契约（侧栏）

侧栏结构由前端实现，后端只提供排序与字段保障：

- 顶部固定「＋ 新对话」；下方固定「最近」分组；本次不出现「项目」分组。
- 草稿态不写服务端、不占列表。
- 首问收到 `meta` 后，前端把会话插入「最近」最前面并高亮；标题取首问前 20 字。
- 列表顺序与 `GET /api/conversations` 的 `update_time DESC` 一致。
- hover 真实会话条目显示小删除按钮，二次确认后调用删除接口；草稿态无删除入口。
- 空态主区为欢迎语 + 可用输入框，不是空白列表面板。

### 移除与保持

- `POST /api/qa` 必须改造为多轮接口，不保留旧的单轮语义。
- 旧静态操作台 `static/index.html` 直接删除，列为删除项，不改造。
- 现有 `sources` / `delta` / `done` / `error` 事件名与载荷保持不变，只新增前置 `meta`。

## Testing Decisions

- 最高测试缝是 HTTP + SSE 协议：用 MockMvc 发起请求，断言状态码、`ApiResponse` 错误码、SSE 事件顺序与载荷字段；不改用更低的私有方法测试。
- 数据库相关验证分三档，与本地 tracker 的票 01 / 11 对齐：
  1. **SQL 契约测试**（票 01）：读取 `docs/schema.sql`，断言两表、两个唯一约束、索引与 `COMMENT ON` 文本存在；只证明 SQL 文本完整，**不证明数据库实际拒绝重复写入**。
  2. **Mapper XML 解析测试**（票 01）：不连库构造 MyBatis 配置，断言 mapper XML 可解析且语句 ID 存在；不覆盖 SQL 语义。
  3. **真库约束验证**（票 11，Testcontainers，暂缓）：一次性 PostgreSQL 容器加载 schema，验证同轮第二条 `ASSISTANT` / `USER` 被拒绝、排序与 JSONB 往返。票 01 阶段用 psql 手工步骤补位，不引入测试依赖。
- 业务规则用服务层单测覆盖，替换策略、线程池与仓储：标题截断、6 轮/2000 字符整轮裁剪、`FAILED`/`STOPPED` 不回放、锁冲突、线程池拒绝落 `FAILED`。
- 现有先例：项目已有 QA 服务测试（Mockito + 假 emitter/策略）与控制器 MockMvc 测试；新用例沿用同一风格，测试名表达外部行为而非实现细节。
- 不测试 SQL 字符串细节（契约测试只断言结构性片段）、不测试私有方法、不为 `SseEmitter` 增加自定义抽象层。

## Out of Scope

- 「项目」分组及其数据模型。
- 登录态、鉴权、多租户；`X-User-Id` 仍是临时方案。
- Redis / 分布式锁；多实例下的同会话并发控制。
- 会话重命名、置顶、搜索、分页、归档、恢复。
- LLM 改写检索 query、跨会话记忆、摘要压缩。
- 首问超时重试的幂等与去重；重复会话是已知限制。
- 前端代码实现（侧栏、草稿态、历史渲染由前端需求文档另行约束）。
- 旧单轮 `/api/qa` 的兼容层。
- 历史消息中引用来源的再次检索与校验；只回放已存快照。

## Further Notes

- 已知限制：首问请求超时但后端已建会话时，前端重发会产生重复会话；MVP 接受，用户可手动删除。
- 已知限制：草稿态刷新即丢。
- 已知限制：`409` 锁只在单实例内有效。
- 原型 `docs/requirements/multi-turn-ui-prototype.html` 中 `meta` 用 0.6 秒定时器模拟，真实实现必须是 SSE 首事件。
- 需求事实源：`docs/requirements/multi-turn-backend.md` 与 `multi-turn-frontend.md`；两端字段必须逐字一致。
