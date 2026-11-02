# 多轮对话改造 · 后端需求文档

> 状态：已定稿（原 12 条决策 + 第 3 条懒创建修订 + 第 8 条 messageId 边界补充）
> 范围：`lender-assistant` 后端
> 配套文档：[multi-turn-frontend.md](./multi-turn-frontend.md)
> 跨端共享的标识、消息结构、时间格式、错误语义以本文第 2 节为准，前端文档逐字引用。

## 1. 目标与非目标

### 1.1 目标

1. 问答从单轮升级为多轮：同一 `conversationId` 下，历史问答参与后续生成。
2. 会话与消息持久化到 PostgreSQL，服务重启、刷新页面、换标签页后历史仍在。
3. 提供会话列表、删除、历史消息读取接口；**不提供显式新建会话接口**，会话在首问受理时懒创建。
4. `POST /api/qa` 改造为多轮 SSE 接口：`conversationId` 可选，缺省表示新会话；首个 `meta` 事件回传新建的 `conversationId`、本轮 `roundId`、用户消息 id 与助手消息 id。
5. 用户标识通过 `X-User-Id` 请求头传递，后端统一解析；跨用户访问会话一律返回 404。
6. 保留现有 SSE 真流式实现与 `sources → delta* → done` 事件语义，仅新增 `meta` 首事件。
7. 删除旧操作台静态页 `src/main/resources/static/index.html`（删除项，不改造）。

### 1.2 非目标（本次不做）

- 模型自动生成会话标题。
- 会话手动改名。
- LLM 改写检索 query 的开关（本次仅使用规则拼接）。
- 多用户鉴权与真实登录体系（`X-User-Id` 仍是开发期标识，前端写死）。
- 会话分页、会话搜索、软删恢复、导出。
- 多实例部署下的分布式并发锁（本次并发控制仅在单实例进程内生效）。
- 首问幂等（`clientRequestId` 等机制，见 1.3）。

### 1.3 已知限制（本次接受，不实现）

1. **首问超时可能产生重复会话**：前端首问未带 `conversationId`，若请求超时但后端已完成会话创建，前端重发会再建一个新会话，列表出现两条记录。MVP 接受此行为（用户可手动删除）；后续增强可引入前端 `clientRequestId` 做幂等。
2. **草稿会话不持久**：未发出首问前不存在服务端记录；前端刷新、关闭页面或切走即丢弃。

## 2. 跨端共享契约（唯一口径）

### 2.1 标识

| 标识 | 生成方 | 格式与约束 | 说明 |
|---|---|---|---|
| `conversationId` | 后端 | UUID v4，36 字符 | **首问受理时生成**，通过 SSE `meta` 回传；前端不得自行生成或提前持有 |
| `messageId` | 后端 | UUID v4，36 字符 | 每条历史消息唯一；SSE `meta` 分别回传本轮 `userMessageId` 与 `assistantMessageId` |
| `roundId` | 后端 | UUID v4，36 字符 | **一轮问答的关联键**：同一轮的用户消息与助手消息共享同一个 `roundId`；SSE `meta` 回传 |
| `userId` | 前端 | `[A-Za-z0-9_-]{1,64}` | 通过请求头 `X-User-Id` 传递；开发期固定值 `dev-user-001` |

- JSON 字段统一使用驼峰（`conversationId`、`messageId`、`roundId`、`userId`）。
- 数据库列统一使用下划线（`conversation_id`、`message_id`、`round_id`、`user_id`）。
- 会话归属校验：携带 `conversationId` 时，不存在、已软删、或 `user_id` 与请求头不一致，一律返回 `404 CONVERSATION_NOT_FOUND`，不暴露会话是否存在。
- **新建路径（未携带 `conversationId`）不做归属校验，也不会返回 404**；后端直接创建新会话。

### 2.2 标识的使用边界

- 前端**不需要、也无法提前知道** `conversationId` 与 `messageId`：两者都由后端在首问受理时生成。
- 流式期间前端用本地临时 key（建议 `tmp-<seq>`）渲染助手气泡与消息列表项；收到 `meta` 后，用 `assistantMessageId` 替换临时 key。
- 历史消息展示、失败重试、后续反馈定位一律以服务端返回的 `messageId` 为准；临时 key 不得持久化、不得进入任何请求参数。
- `meta` 必须携带 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId` 四项，并且必须是流的第一个事件——这是它不能并入 `done` 的根本原因：前端需要在首 token 之前拿到会话 id 与轮次/消息 id，完成"草稿态转正"与气泡绑定。
- `meta.assistantMessageId` 与历史消息中的该条助手消息 `messageId` 完全一致；`meta.userMessageId` 同理对应本轮用户消息；两者共享 `meta.roundId`。
- **一轮的对应关系是显式字段 `roundId`，不是靠 `create_time` 或自增 `id` 相邻推断**：同一轮的 `USER` 与 `ASSISTANT` 行写同一个 `round_id`，并受唯一约束 `(conversation_id, round_id, role)` 保护。

### 2.3 时间格式

- 所有对外时间字段（`createTime`、`updateTime`）由后端格式化为 `YYYY-MM-DD HH:mm:ss`，无时区后缀，前端按本地时间解析。

### 2.4 消息状态

| 值 | 适用角色 | 含义 |
|---|---|---|
| `DONE` | USER / ASSISTANT | 用户消息落库即为此状态；助手消息正常生成结束 |
| `FAILED` | ASSISTANT | 生成过程中抛出异常，保留已生成的部分正文 |
| `STOPPED` | ASSISTANT | 客户端主动停止或连接断开，保留已生成的部分正文 |

**上下文回放规则**：只有「用户消息 `DONE` + 紧随其后的助手消息 `DONE`」组成的完整轮次才进入模型上下文；`FAILED` / `STOPPED` 轮次只展示、不回放。

### 2.5 消息结构（GET 历史消息与落库共用）

```json
{
  "messageId": "3f1c2a90-...",
  "roundId": "7d0f...",
  "role": "USER | ASSISTANT",
  "content": "消息正文",
  "status": "DONE | FAILED | STOPPED",
  "sources": [
    {
      "idx": 1,
      "docTitle": "资方接口文档",
      "sectionPath": "3 日末文件 > 3.2 逾期文件格式",
      "chunkId": 123,
      "content": "切片正文",
      "vectorDistance": 0.1234,
      "keywordHits": 2,
      "bothChannels": true,
      "rrfScore": "0.03279"
    }
  ],
  "provider": "deepseek-chat",
  "elapsedMillis": 1234,
  "error": null,
  "createTime": "2026-11-02 20:00:00"
}
```

- 同一轮的两条消息 `roundId` 相同；不同轮次 `roundId` 不同。`roundId` 不随消息状态变化，`FAILED` / `STOPPED` 的助手消息也保留本轮 `roundId`。
- `USER` 消息：`sources` / `provider` / `elapsedMillis` / `error` 恒为 `null`。
- `ASSISTANT` 消息：`sources` 为数组（未召回时为空数组，不省略字段）；`provider`、`elapsedMillis` 在 `DONE` 时有值，失败/停止时可为 `null`；`error` 仅 `FAILED` 时非空。
- `sources` 元素结构与现有 SSE `sources` 事件载荷完全一致（`QaSourceResponse`）。

### 2.6 非 SSE 错误响应

沿用统一包装：

```json
{ "code": 409, "msg": "该会话正在生成回答，请稍后", "data": null }
```

| code | HTTP | 场景 |
|---|---|---|
| `PARAM_INVALID` = 400 | 400 | `X-User-Id` 缺失/空白/格式非法；`conversationId` 传了但不是 UUID；`question` 空白；`topK` ≤ 0；JSON 不可解析 |
| `CONVERSATION_NOT_FOUND` = 404 | 404 | **仅当请求携带 `conversationId`**：会话不存在 / 已删除 / 不属于当前 `userId` |
| `CONVERSATION_BUSY` = 409 | 409 | 携带的 `conversationId` 已有进行中的流式问答 |
| `INTERNAL_ERROR` = 500 | 500 | 未预期异常 |

- 新建路径（不携带 `conversationId`）不会出现 404 / 409。

## 3. 数据结构（PostgreSQL）

新增两张表，追加到 `docs/schema.sql`（该文件是建表唯一事实源，必须同步回写并保持幂等）。

### 3.1 `conversation`

```sql
CREATE TABLE IF NOT EXISTS conversation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_conversation_conversation_id UNIQUE (conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_user_status_update
    ON conversation (user_id, status, update_time DESC);
```

约束与要点：

- `status` 取值 `ACTIVE` / `DELETED`，仅供内部使用，不对外暴露；删除为软删。
- 会话只在首问受理时创建，创建时 `title` 直接取首问正文前 20 个字符（按 Unicode code point 计，去除首尾空白）；**库中不存在"新会话"这种无首问的空记录**。
- 每次用户消息落库、每轮助手消息落库，都刷新 `update_time`。
- `conversation_id` 由后端在首问受理时生成；数据库唯一约束兜底防重。

### 3.2 `message`

```sql
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id TEXT        NOT NULL,
    round_id        TEXT        NOT NULL,
    message_id      TEXT        NOT NULL,
    role            TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    sources         JSONB,
    provider        TEXT,
    elapsed_ms      INT,
    error_msg       TEXT,
    create_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_message_message_id UNIQUE (message_id),
    CONSTRAINT uk_message_round_role UNIQUE (conversation_id, round_id, role),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (conversation_id)
);
```

索引与要点：

```sql
CREATE INDEX IF NOT EXISTS idx_message_conversation_id
    ON message (conversation_id, id);
```

- `role` 取值 `USER` / `ASSISTANT`；`status` 见 2.4。
- `round_id` 是轮次关联键：同一轮的用户消息与助手消息共享同一个 `round_id`，`roundId` 由后端在受理提问时生成并同时用于 SSE `meta`。
- `(conversation_id, round_id, role)` 唯一约束保证一轮最多一条用户消息、一条助手消息；`round_id` 不承担跨会话唯一性，只在本会话内有意义。
- `sources` 仅 `ASSISTANT` 消息写入，内容为 2.5 中 `sources` 数组的 JSON 快照；用户消息为 `NULL`。
- 历史消息一律按 `id ASC` 排序（自增 id 即会话内时序，避免同一秒内 `create_time` 并列导致顺序不稳定）；轮次归属只认 `round_id`，不靠相邻顺序推断。
- 用户消息在首问/后续提问受理时立即插入；助手消息在生成结束（正常 / 异常 / 停止）时插入。
- 用户消息 `messageId`、助手消息 `messageId` 与 `roundId` 均在提交流式任务前生成；用户消息先落库，助手消息结束后落库，但两者始终写同一个 `round_id`。
- 不物理删除消息；会话软删后消息保留。
- 新表必须有 `COMMENT ON TABLE/COLUMN`，符合 `docs/agents/coding-standards.md` §6.8。

### 3.3 历史上下文装配

- 取当前用户消息之前、最近 **6 轮**、且同一 `round_id` 下 `USER` / `ASSISTANT` 两条消息均为 `DONE` 的完整轮次，按时间正序回放。
- 轮次聚合规则：按 `round_id` 分组，每组必须恰好包含一条 `USER` 与一条 `ASSISTANT`；缺任一条（如助手落库失败）整轮丢弃，不与相邻轮次拼接。
- 总字符上限 **2000 字符**（仅统计历史 Q/A 正文）；超限时从最旧的一轮开始整轮丢弃。
- 新建会话没有历史，`history` 为空。
- 配置项写入 `application.yml`：

```yaml
rag:
  qa:
    history-max-rounds: 6
    history-max-chars: 2000
```

- 检索 query 规则：取上下文中最近一条用户消息正文 + `"\n"` + 当前问题拼接；无可用历史时只用当前问题。不做 LLM 改写。

## 4. 接口契约

所有接口（除 SSE 流本身外）返回统一包装 `{code, msg, data}`；所有接口必须携带 `X-User-Id`。

### 4.1 会话列表

`GET /api/conversations`

- 无查询参数、不分页。
- 仅返回当前 `userId` 下 `status = 'ACTIVE'` 的会话，按 `update_time DESC, id DESC` 排序。
- 成功响应 `200`，`data` 为数组：

```json
[
  {
    "conversationId": "3f1c2a90-...",
    "title": "首套贷款成数上限",
    "createTime": "2026-11-02 20:00:00",
    "updateTime": "2026-11-02 20:05:00"
  }
]
```

- 无会话时返回空数组，不返回 404。

### 4.2 删除会话（软删）

`DELETE /api/conversations/{conversationId}`

- 成功响应 `200`：

```json
{
  "code": 200,
  "msg": "ok",
  "data": { "conversationId": "3f1c2a90-...", "deleted": true }
}
```

- 行为：将 `status` 置为 `DELETED`、刷新 `update_time`；消息不删除。
- 错误：`404 CONVERSATION_NOT_FOUND`（不存在、已删除、非本人）；重复删除同样返回 404。
- 该会话有进行中的流式问答时：当前轮允许跑完，但结束时**跳过落库**；后续对它的任何请求返回 404。
- 草稿会话不存在于服务端，前端切走即丢弃，**不会调用本接口**。

### 4.3 读取历史消息

`GET /api/conversations/{conversationId}/messages`

- 成功响应 `200`：

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "conversationId": "3f1c2a90-...",
    "messages": [ { "messageId": "...", "role": "USER", "content": "...", "status": "DONE", "sources": null, "provider": null, "elapsedMillis": null, "error": null, "createTime": "2026-11-02 20:00:00" }, { "messageId": "...", "role": "ASSISTANT", "content": "...", "status": "DONE", "sources": [ ... ], "provider": "deepseek-chat", "elapsedMillis": 1234, "error": null, "createTime": "2026-11-02 20:01:00" } ]
  }
}
```

- `messages` 按 `id ASC` 扁平返回（不按轮分组）；每条消息都带 `roundId`，前端按 `roundId` 自行聚合出"问题—回答—引用"。
- 同一轮的两条消息在数组中相邻，但接口契约不依赖相邻性；渲染与重试必须使用 `roundId` / `messageId` 显式关联。
- 空会话返回 `messages: []`，不返回 404。
- 错误：`404 CONVERSATION_NOT_FOUND`。

### 4.4 多轮问答（SSE，首问懒创建）

`POST /api/qa`，`Content-Type: application/json`，`Accept: text/event-stream`。

请求体：

```json
{ "conversationId": "3f1c2a90-...", "question": "那它的费率是多少", "topK": 4 }
```

- `conversationId` **可选**：
  - 缺省（或 `null`）→ 新会话首问：后端在受理时创建会话并生成 `conversationId`，通过 `meta` 回传。
  - 提供 → 必须是 UUID v4 且属于当前 `userId`、未删除；否则 `404 CONVERSATION_NOT_FOUND`；格式非法 → `400 PARAM_INVALID`。
- `question` 必填，`trim` 后不得为空 → 否则 `400 PARAM_INVALID`。
- `topK` 可选，默认 `4`（`SearchConstants.DEFAULT_TOP_K`）；≤ 0 → `400 PARAM_INVALID`（沿用 `TopKUtils.resolve`）。
- 携带的会话已有进行中的问答 → `409 CONVERSATION_BUSY`。
- 新建路径不产生 404 / 409。

**错误发生时机**：以上 `400/404/409` 均在返回 `SseEmitter` 之前同步抛出，因此响应是普通 JSON 错误体，不是 SSE 流。前端必须先检查 `response.ok`，再解析流。

**成功时的 SSE 事件序列**：

```text
event: meta
data: {"conversationId":"3f1c2a90-...","roundId":"7d0f...","userMessageId":"6a2e...","assistantMessageId":"8b7d..."}

event: sources
data: [ {"idx":1,"docTitle":"...","sectionPath":"...","chunkId":123,"content":"...","vectorDistance":0.12,"keywordHits":2,"bothChannels":true,"rrfScore":"0.03279"} ]

event: delta
data: 模型 token 增量文本

event: delta
data: ...

event: done
data: {"provider":"deepseek-chat","elapsedMillis":1234,"sourceCount":3}
```

- `meta` 必须是第一个事件，载荷固定为 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId` 四字段。
- `roundId` 是本轮用户消息与助手消息共享的关联键；`userMessageId` / `assistantMessageId` 都必须挂在同一个 `roundId` 下。
- 首问时 `conversationId` 是本次新建的 id；后续轮次与请求中的 id 相同。
- 未召回任何资料时：`meta → delta（固定提示文案）→ done（sourceCount = 0）`，助手消息以 `DONE` 落库、`sources = []`。
- 生成异常时：在已建立流的前提下发送 `error`（纯文本），随后断流；助手消息以 `FAILED` 落库，保留已生成正文。
- 客户端停止/连接断开：服务端发送失败即停止推送，助手消息以 `STOPPED` 落库并保留已生成正文。
- `done` 载荷沿用现有三字段（`provider`、`elapsedMillis`、`sourceCount`），不新增字段。

## 5. 后端改动面

### 5.1 数据库与配置

- `docs/schema.sql`：新增 `conversation`、`message` 两表、索引、外键与 `COMMENT ON`（见第 3 节）。
- `src/main/resources/application.yml`：新增 `rag.qa.history-max-rounds` 与 `rag.qa.history-max-chars`。

### 5.2 常量与错误码

- 角色、消息状态、会话状态以字符串字面量落库（`USER` / `ASSISTANT`、`DONE` / `FAILED` / `STOPPED`、`ACTIVE` / `DELETED`），与 `docs/schema.sql` 的列注释一致；不额外定义枚举类。
- `common/exception/ErrorCode` 新增：
  - `CONVERSATION_NOT_FOUND(404, "会话不存在")`
  - `CONVERSATION_BUSY(409, "会话正在生成回答，请等待本轮结束后再提问")`
- 现有 `GlobalExceptionHandler` 无需改动：`HttpStatus.resolve(409)` 可直接映射 HTTP 409。

### 5.3 用户标识解析

- 新增 `common/web/UserIdInterceptor`：校验 `X-User-Id` 请求头，合法则写入 request attribute `userId`，非法抛 `BizException(PARAM_INVALID)`。
- 新增 `config/WebMvcConfig` 注册拦截器，作用路径：`/api/conversations/**`、`/api/qa`。
- Controller 通过 `@RequestAttribute("userId") String userId` 取用户标识；不使用 ThreadLocal，避免异步线程泄漏。

### 5.4 会话领域

新增 `conversation` 领域包（结构遵循 `docs/agents/coding-standards.md` §0）：

```text
conversation/
├── controller/ConversationController          # 4.1 ~ 4.3 三个接口（无新建接口）
├── service/ConversationService + impl/        # 会话列表/软删/历史读取/懒创建/落库
├── mapper/ConversationMapper + MessageMapper
├── model/ConversationRow、MessageRow
└── vo/response/ConversationResponse、ConversationDeleteResponse、MessageListResponse、MessageResponse
```

- `ConversationService` 对外提供：
  - `ConversationRow createWithFirstQuestion(String userId, String question)`：生成 `conversationId`、以首问前 20 字符为标题、插入会话；与用户消息插入放在同一事务。
  - `void ensureOwnedActive(String userId, String conversationId)`：归属/存在性校验，失败抛 `CONVERSATION_NOT_FOUND`。
  - `List<ConversationResponse> list(String userId)`
  - `void delete(String userId, String conversationId)`
  - `MessageListResponse listMessages(String userId, String conversationId)`
  - `List<ChatTurn> loadHistory(String userId, String conversationId, long beforeMessageId)`：按 `roundId` 聚合成轮，只返回两条消息均 `DONE` 的完整轮次。
  - `void appendUserMessage(String conversationId, String roundId, String messageId, String content)` / `void appendAssistantMessage(String conversationId, String roundId, ...)` / `void touch(...)`
- `roundId` 由 `QaService` 在每轮受理时生成，随用户消息与最终的助手消息一起落库；`ConversationService` 不自行生成轮次 id。
- 多表写（会话创建 + 用户消息、消息 + 会话更新时间）加 `@Transactional(rollbackFor = Exception.class)`；事务内不得调用模型。
- 历史轮次的聚合、完整性校验（必须一问一答）与字符截断在 Service 层完成。

### 5.5 问答链路

- `rag/vo/request/QaRequest` 增加可空的 `conversationId`。
- `rag/controller/QaController` 增加 `@RequestAttribute("userId")`，透传 `conversationId`。
- `rag/service/QaService#answer` 签名改为：

```java
SseEmitter answer(String userId, String conversationId, String question, int topK);
```

`conversationId == null` 表示新会话首问。

- `rag/service/impl/QaServiceImpl` 编排（保持"立即 return emitter + 独立线程池"的现有实现）：

**请求线程（同步，异常走普通 JSON）**
1. `conversationId` 非空：校验 UUID 格式（`400`）与归属/未删（`404`）；为空：跳过校验，进入懒创建路径。
2. 生成 `conversationId`（仅新建路径）、本轮 `roundId`、`userMessageId`、`assistantMessageId`。
3. 占用生成锁（`409`）；锁为进程内 `ConcurrentHashMap.newKeySet()`，键为 `conversationId`。
4. 落库：
   - 新建路径：`createWithFirstQuestion` + 插入用户消息（`DONE`），同一事务。
   - 已有路径：插入用户消息（`DONE`）并刷新会话 `update_time`。
5. 以刚插入的用户消息 id 为上界装配历史上下文（`loadHistory` 只取该 id 之前的完整 `DONE` 轮次；新建路径为空）。
6. 创建 `SseEmitter` 并提交到现有 `streamExecutor`；若线程池拒绝，将助手消息落库为 `FAILED`（`error = "服务繁忙，请重试"`）、释放锁、返回 500，避免留下无回复的会话。

**工作线程**
1. 推送 `meta`（`conversationId`、`roundId`、`userMessageId`、`assistantMessageId`）。
2. 按 3.3 规则拼接检索 query，执行 `SearchService.hybridSearch`。
3. 召回为空：推送固定提示 `delta` + `done(sourceCount=0)`，落库助手消息 `DONE`。
4. 有召回：推送 `sources`、流式 `delta`、`done`，落库助手消息 `DONE`（含 `sources` JSON、`provider`、`elapsedMillis`）。
5. 异常：尽量推送 `error` 后断流，落库助手消息 `FAILED`。
6. 发送失败（客户端断开）：停止推送，落库助手消息 `STOPPED`。
7. 任何路径都在 `finally` 中刷新会话 `update_time`、释放生成锁；锁释放必须幂等。

### 5.6 策略接口变更（编译面最大）

- `rag/strategy/AnswerGeneratorStrategy#generate` 签名改为：

```java
void generate(String systemPrompt, List<ChatTurn> history, String question,
              List<SearchItemResponse> contextHits, Consumer<String> onDelta);
```

- 新增 `rag/model/ChatTurn`：`record ChatTurn(MessageRole role, String content)`。
- `OpenAiCompatibleAnswerGeneratorStrategy`：把 `history` 按 `USER` / `ASSISTANT` 依次映射为模型消息，再接当前轮用户消息（召回资料 + 当前问题仍在最后一条用户消息中）。
- `MockAnswerGeneratorStrategy`：同步改造签名，历史不参与 Mock 输出（保持可预测）。
- 同步更新全部单测：`QaServiceImplTest`、`OpenAiCompatibleAnswerGeneratorStrategyTest`、`MockAnswerGeneratorStrategyTest`，并新增 `ConversationServiceImplTest`。
- 收尾执行 `mvn clean test`，确保编译期扫到所有调用点；已知 Maven 增量编译会用旧 `.class` 假报成功。

### 5.7 删除项

- 删除 `src/main/resources/static/index.html`（旧单轮操作台）。不改造、不保留兼容入口。

### 5.8 sources 存储

- `sources` 以 `JSONB` 落库：Service 用 `ObjectMapper` 把 `List<QaSourceResponse>` 序列化为 JSON 字符串，Mapper XML 中 `CAST(#{sourcesJson} AS jsonb)` 写入；读取时取字符串再反序列化为对象。
- 序列化/反序列化只发生在 `mapper` 边界，Service 与 Controller 始终使用结构化对象，符合"禁止跨层先序列化再解析"的约束。

## 6. 可测试的验收标准

### 6.1 会话接口

1. 空库调用 `GET /api/conversations` 返回 `[]`；不存在任何隐式创建的会话。
2. 列表只返回当前 `X-User-Id` 的 `ACTIVE` 会话，按 `updateTime` 倒序。
3. 用户 A 的会话不出现在用户 B 的列表中。
4. `DELETE` 后该会话从列表消失；再次 `DELETE` 返回 404；数据库 `status = 'DELETED'`，`message` 行仍存在。
5. 缺少 `X-User-Id` 时任一接口返回 400；头值为空白或含非法字符同样 400。

### 6.2 懒创建与会话列表

6. 不调用任何创建接口的前提下，仅发出首问（不带 `conversationId`），列表由空变为 1 条，`title` 等于首问前 20 个字符。
7. 首问的 `meta.conversationId` 与随后列表中的 `conversationId` 一致；`meta.userMessageId`、`meta.assistantMessageId` 分别与历史消息中的两个 id 一致。
8. 首问成功后，`GET .../messages` 返回 2 条：`USER`（`DONE`）与 `ASSISTANT`（`DONE`）。
9. 首问未带 `conversationId` 时不产生 404 / 409，也不会命中任何归属校验分支。
10. 已知限制用例：模拟"后端已创建、前端未收到 `meta`"后重发首问（仍不带 id），列表出现 2 条会话；该行为被本文档接受，不视为缺陷。

### 6.3 历史消息

11. `sources` 经 JSONB 存取后与 SSE `sources` 事件元素逐字段一致。
12. 未召回轮次的助手消息 `sources = []`、`status = DONE`。
13. `FAILED` / `STOPPED` 轮次仍出现在历史消息中，且正文保留已生成部分。

### 6.4 多轮上下文

14. 同一会话连续 7 轮问答，第 7 轮传给模型的 `history` 只包含最近 6 轮的完整 `DONE` 轮次。
15. 历史总字符超 2000 时，从最旧整轮开始丢弃，且不会出现只有用户消息、没有助手回复的孤立上下文。
16. `FAILED` / `STOPPED` 的轮次不进入下一轮 `history`。
17. 第二轮检索调用收到的 query 等于"上一轮用户问题 + `\n` + 当前问题"；首轮等于当前问题。

### 6.5 SSE 与并发

18. 成功问答的事件序列严格为 `meta → sources → delta* → done`；`meta` 是首个事件且含 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`。
19. 未召回时序列为 `meta → delta → done(sourceCount=0)`。
20. 对同一已有会话并发发起两次 `POST /api/qa`，第二次返回 HTTP 409 + `code=409`，且不产生第二条用户消息。
21. 携带不存在的会话、他人会话、已软删会话均返回 HTTP 404 + `code=404`，响应体为 JSON 而非 SSE。
22. 客户端在流式进行中断开（`AbortController`），服务端最终落库一条 `STOPPED` 助手消息；锁被释放，随后可再次提问。
23. 模型异常时落库 `FAILED` 助手消息，`error` 字段非空；同一会话下一轮不受该轮影响。
24. 完成后会话 `update_time` 已刷新，列表排序位置更新。
25. 线程池拒绝任务时，本轮助手消息落库为 `FAILED`，不会留下"只有用户消息、没有助手回复"的会话。

### 6.6 轮次关联

26. 同一轮的 `USER` 与 `ASSISTANT` 两条消息 `round_id` 完全相同；不同轮次 `round_id` 互不相同。
27. 对同一 `(conversation_id, round_id, role)` 重复插入时数据库唯一约束报错，接口层不会产生“一轮多答”或“一轮多问”。
28. 人为制造“只有 USER、没有 ASSISTANT”的残轮后再发起下一轮：`loadHistory` 丢弃该残轮，不会把下一轮的助手消息配给上一轮的用户问题。
29. 两条消息 `create_time` 相同或乱序时，`GET .../messages` 仍按 `id ASC` 返回，且仅凭 `roundId` 即可正确配对，不依赖相邻下标。
30. `FAILED` / `STOPPED` 的助手消息保留本轮 `round_id`，且与对应 `USER` 消息的 `round_id` 一致。

### 6.7 工程收尾

31. `src/main/resources/static/index.html` 已删除，仓库中无任何引用。
32. `docs/schema.sql` 已同步新增表，空库执行后可直接启动应用。
33. `mvn clean test` 全绿；`AnswerGeneratorStrategy` 的所有实现与测试均已适配新签名。

## 7. 跨端契约：前端必须遵守的部分

1. 所有会话接口与 `/api/qa` 必须携带 `X-User-Id` 请求头，值来自前端唯一常量，不得在请求体重复传 `userId`。
2. 前端不得自行生成 `conversationId` 或 `messageId`；新建会话由用户点击后进入本地草稿态（无 id、不落库），首问请求不带 `conversationId`。
3. 首问的 `conversationId` 只能从 `meta` 获取；收到 `meta` 后立即把草稿态转为真实会话，并用 `assistantMessageId` 替换本地临时 key。
4. `meta` 是首个事件，固定读取 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`；`done` 只读取现有三字段。
5. 收到 HTTP `404` 仅可能是"携带了已有 id"的场景：从会话列表移除并提示，不得自动重试。
6. 收到 HTTP `409` 时：提示"该会话正在生成回答，请稍后"，不得自动重试；恢复用户输入内容以便重发。
7. `sources` 字段按 2.5 结构解析；`FAILED` / `STOPPED` 消息仅展示，不再用于生成。
8. 切换会话、刷新页面后以 `GET .../messages` 为唯一数据源，不把本地缓存当作事实；草稿态不进入列表。
9. 用户主动停止即视为 `STOPPED`；前端保留半截正文，不把该轮当作完成。
10. 删除当前会话前先中止流式请求；删除后选择列表中最近的会话或回到空态。草稿态不得调用删除接口。
11. `topK` 不传或传正整数；不得传 0 或负数。
12. 首问超时重试会产生重复会话（1.3 已知限制），前端不做去重或幂等补偿；用户可手动删除多余会话。
13. 历史消息接口返回扁平数组且每条都带 `roundId`；前端必须按 `roundId` 分组渲染“问题—回答—引用”，不得用数组相邻下标配对。

## 8. 分阶段实施顺序（★ 为可并行）

| 阶段 | 后端 | 前端 | 并行关系 |
|---|---|---|---|
| P0 | 本文档定稿 | 本文档 + 前端文档定稿 | 已完成 |
| P1 | schema 新增表、ErrorCode、常量枚举、`UserIdInterceptor` | 共享类型、`DEV_USER_ID` 常量、请求头注入 | ★ 前后端可同时开工 |
| P2 | 会话列表、软删、历史消息接口 + 单测（不含懒创建） | 会话侧栏、草稿态、切换/删除、历史渲染 | ★ P2 阶段双方并行，依赖 P1 契约 |
| P3 | 首问懒创建、`meta` 四字段、`AnswerGeneratorStrategy` 带 history、落库与锁 | 首问流程、`meta` 解析、临时 key 替换、草稿转正、列表刷新 | ★ P2 完成后双方并行开发，联调放在 P4 |
| P4 | 前后端联调：懒创建、SSE 全序列、停止、并发 409、归属 404 | 同左 | 联调，不并行 |
| P5 | 删除 `static/index.html`；`docs/schema.sql` 回写核对；`mvn clean test` | 端到端回归（含 mock 模式） | 可并行 |
