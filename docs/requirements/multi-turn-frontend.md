# 多轮对话改造 · 前端需求文档

> 状态：已定稿（原 12 条决策 + 第 3 条懒创建修订 + 第 8 条 messageId 边界补充）
> 范围：`lender-assistant-frontend`
> 配套文档：[multi-turn-backend.md](./multi-turn-backend.md)
> 跨端共享的标识、消息结构、时间格式、错误语义与后端文档第 2 节逐字一致。

## 1. 目标与非目标

### 1.1 目标

1. 对话页从单轮升级为多轮：会话列表、新建（本地草稿）、删除、切换会话。
2. 每个会话展示并继续自己的历史消息，刷新页面后历史仍在。
3. 会话标识使用后端返回的 `conversationId`：首问前不存在，首问后从 SSE `meta` 获取。
4. 用户标识使用请求头 `X-User-Id`，开发期写死常量。
5. 解析 SSE `meta` 首事件，把草稿态转为真实会话，并用服务端 `assistantMessageId` 替换本地临时 key。
6. 历史消息完整渲染：用户提问、助手回答、【来源N】引用、右侧检索来源面板。
7. 保留现有停止、失败、未召回、重试的交互能力，并补齐 404 / 409 分支。
8. 现有 mock 模式同步支持懒创建语义，保证无后端时页面仍可完整演示。

### 1.2 非目标（本次不做）

- 模型自动生成会话标题。
- 会话手动改名。
- LLM 改写检索 query 的开关（前端不提供该开关，也不展示相关状态）。
- 多用户鉴权与真实登录（不做登录页、token、用户切换）。
- 会话分页、搜索、软删恢复、导出、消息重新生成。
- 会话列表的拖拽排序、置顶、未读标记。
- 首问幂等（`clientRequestId` 等机制，见 1.3）。

### 1.3 已知限制（本次接受，不实现）

1. **首问超时可能产生重复会话**：首问未带 `conversationId`，若请求超时但后端已创建会话，前端重发会再建一条会话，列表出现两条记录。MVP 接受此行为（用户可手动删除多余会话）；后续增强可引入前端 `clientRequestId` 做幂等。
2. **草稿会话不持久**：点击"新建会话"只进入本地草稿态，未发出首问前不落库、不进列表；刷新页面、关闭页面或切走即丢弃。

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
- 会话归属校验由后端完成：**携带了已有 `conversationId` 时**，不存在/已删除/非本人一律按 404 处理；草稿态首问不携带 id，不会出现 404。

### 2.2 标识的使用边界

- 前端**不需要、也无法提前知道** `conversationId` 与 `messageId`；两者都由后端在首问受理时生成。
- 草稿态没有 `conversationId`：不进会话列表、不调用删除接口、不写本地持久化。
- 流式期间助手气泡/消息列表项使用本地临时 key（`tmp-<seq>`）；收到 `meta` 后，用 `assistantMessageId` 替换临时 key。
- 历史消息展示、失败重试、后续反馈定位一律以服务端 `messageId` 为准；临时 key 不持久化、不进入任何请求参数。
- `meta` 是流的第一个事件，固定携带 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`；`conversationId` 在首问时就是本次新建的值。
- `meta.assistantMessageId` 与历史消息中该条助手消息的 `messageId` 完全一致；`meta.userMessageId` 同理对应本轮用户消息；两者共享 `meta.roundId`。
- **一轮的对应关系是显式字段 `roundId`**：同一轮的用户消息与助手消息共享同一个 `roundId`，前端不得靠 `createTime` 或数组相邻下标推断。

### 2.3 时间格式

- 所有对外时间字段（`createTime`、`updateTime`）格式为 `YYYY-MM-DD HH:mm:ss`，无时区后缀，前端按本地时间解析（复用 `lib/format.ts` 的 `parseServerTime`）。

### 2.4 消息状态

| 值 | 适用角色 | 含义 |
|---|---|---|
| `DONE` | USER / ASSISTANT | 用户消息落库即为此状态；助手消息正常生成结束 |
| `FAILED` | ASSISTANT | 生成中异常，保留已生成正文 |
| `STOPPED` | ASSISTANT | 用户主动停止或连接断开，保留已生成正文 |

**上下文回放规则（前端只需知晓）**：只有完整 `DONE` 轮次进入模型上下文；`FAILED` / `STOPPED` 轮次仅展示。

### 2.5 消息结构（TS 镜像）

```ts
export type MessageRole = 'USER' | 'ASSISTANT';
export type MessageStatus = 'DONE' | 'FAILED' | 'STOPPED';

export interface QaSourceItem {
  /** 与正文【来源N】严格一致 */
  idx: number;
  docTitle: string;
  /** 可能为 null：该切片不属于任何小节 */
  sectionPath: string | null;
  chunkId: number;
  content: string;
  /** 仅向量通道命中时有值 */
  vectorDistance: number | null;
  /** 关键词通道命中数；未命中时为 null */
  keywordHits: number | null;
  bothChannels: boolean;
  /** 5 位小数字符串 */
  rrfScore: string;
}

export interface MessageItem {
  messageId: string;
  /** 本轮关联键：同一轮 USER / ASSISTANT 共享 */
  roundId: string;
  role: MessageRole;
  content: string;
  status: MessageStatus;
  /** USER 消息恒为 null；ASSISTANT 为数组，未召回时为 [] */
  sources: QaSourceItem[] | null;
  /** 仅 ASSISTANT 且 DONE 时可能有值 */
  provider: string | null;
  elapsedMillis: number | null;
  /** 仅 FAILED 时非空 */
  error: string | null;
  /** 'YYYY-MM-DD HH:mm:ss' */
  createTime: string;
}
```

- `sources` 元素结构与现有 SSE `sources` 事件载荷一致，`api/qa.ts` 的解析函数可复用。
- 字段缺失值一律表达为 `null`，不用 `0` 或空串顶替。

### 2.6 非 SSE 错误响应

```json
{ "code": 409, "msg": "该会话正在生成回答，请稍后", "data": null }
```

| code | HTTP | 前端处理 |
|---|---|---|
| `PARAM_INVALID` = 400 | 400 | 展示服务端 `msg`；`X-User-Id` 缺失属于开发期配置错误 |
| `CONVERSATION_NOT_FOUND` = 404 | 404 | 仅出现在携带已有 id 的请求：移除该会话、提示"会话不存在或已删除"，回到最近会话或空态 |
| `CONVERSATION_BUSY` = 409 | 409 | 仅出现在已有会话：提示"该会话正在生成回答，请稍后"；恢复输入框内容，不自动重试 |
| `INTERNAL_ERROR` = 500 | 500 | 沿用现有失败展示，可手动重试 |

## 3. 接口契约（前端消费侧）

所有请求必须携带 `X-User-Id`；除 SSE 外均走现有 `request<T>()`（解包 `{code,msg,data}`）。**不存在新建会话接口。**

### 3.1 会话列表

`GET /api/conversations`

- 入参：无。
- 出参 `data`：`ConversationSummary[]`，已按 `updateTime` 倒序；无会话时为空数组。

```ts
export interface ConversationSummary {
  conversationId: string;
  title: string;
  /** 'YYYY-MM-DD HH:mm:ss' */
  createTime: string;
  updateTime: string;
}
```

### 3.2 删除会话

`DELETE /api/conversations/{conversationId}`

- 出参 `data`：`{ conversationId: string; deleted: true }`。
- 重复删除返回 404，按 2.6 处理。
- 草稿态无 id，不调用本接口。

### 3.3 历史消息

`GET /api/conversations/{conversationId}/messages`

- 出参 `data`：

```ts
export interface MessageList {
  conversationId: string;
  /** 按 id ASC 扁平返回，不按轮分组；每条带 roundId */
  messages: MessageItem[];
}
```

- 空会话返回 `messages: []`。
- 前端按 `roundId` 分组：每组取一条 `USER` 与一条 `ASSISTANT` 组成一轮，“引用”取自 `ASSISTANT.sources`；组内缺失任一角色时整轮跳过，不与相邻轮拼接。

### 3.4 多轮问答（SSE）

`POST /api/qa`

请求体（草稿态首问）：

```json
{ "question": "首套贷款成数上限是多少", "topK": 4 }
```

请求体（已有会话）：

```json
{ "conversationId": "3f1c2a90-...", "question": "那它的费率是多少", "topK": 4 }
```

- `conversationId` 可选：草稿态必须**省略该字段**（或传 `null`），由后端创建会话；已有会话必须携带。
- `question` 必填且 `trim` 后非空；`topK` 可选，默认 4。
- 携带已有 id 时，校验失败、会话不存在、并发占用会在流开始**之前**以普通 JSON 返回 `400 / 404 / 409`，因此必须先判断 `response.ok`，再进入 SSE 解析。
- 草稿态首问不会返回 404 / 409。

成功时事件序列：

```text
event: meta
data: {"conversationId":"3f1c2a90-...","roundId":"7d0f...","userMessageId":"6a2e...","assistantMessageId":"8b7d..."}

event: sources
data: [ {"idx":1,"docTitle":"...","sectionPath":"...","chunkId":123,"content":"...","vectorDistance":0.12,"keywordHits":2,"bothChannels":true,"rrfScore":"0.03279"} ]

event: delta
data: 模型 token 增量文本

event: done
data: {"provider":"deepseek-chat","elapsedMillis":1234,"sourceCount":3}
```

- `meta` 恒为第一个事件，固定含 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId` 四字段。
- 未召回：`meta → delta（固定提示）→ done（sourceCount=0）`。
- 异常：`meta → [sources] → delta* → error（纯文本）`，随后断流。
- 用户停止：前端 `AbortController.abort()`；本地保留已生成内容，标记为停止；服务端最终落库 `STOPPED`。

## 4. 页面与交互

### 4.1 对话页布局

在现有 `ChatPage` 内新增会话侧栏，位于主对话区左侧：

```text
AppShell 左侧导航(224px) | 会话侧栏(240px，可折叠) | 对话主区(1fr) | 检索面板(300px，可折叠)
```

侧栏自上而下固定为三段：

1. **顶部「＋ 新对话」入口**：置顶、常驻，只负责进入草稿态，不创建服务端记录；再次点击视为清空当前草稿并保持草稿态。
2. **「最近」分组**：真实会话列表，按 `updateTime DESC, id DESC` 排列（与 `GET /api/conversations` 一致）；分组标题固定显示「最近」，空列表时保留标题并显示分组内空文案。
3. **「项目」分组**：本次不做，侧栏不出现该分组标题、占位或禁用项，留作后续。

列表项与交互：

- 每项展示标题（单行省略）与最近更新相对时间。
- 当前真实会话高亮。
- **hover 时在条目右侧显示一个小尺寸删除图标**，移出即隐藏；未 hover 时不显示任何操作图标。
- 删除入口只对「最近」中的真实会话存在；草稿态不在列表中占位，因此没有删除入口。
- 窄屏（<1280px）允许侧栏折叠为图标按钮，不要求复杂响应式。

### 4.2 进入页面

1. 调 `GET /api/conversations`。
2. 有会话：选中列表中最近更新的会话，调 `GET .../messages` 渲染历史。
3. 无会话：主区展示空态欢迎语（标题 + 一句说明 + 示例问题），底部输入框可用；**不是一块空白列表面板**。侧栏「最近」分组为空但保留标题。用户直接输入问题即按草稿首问处理（请求不带 `conversationId`），成功后会话出现在「最近」分组顶部；**不自动提前进入草稿态**。
4. 列表加载失败：展示错误与重试按钮，不自动创建会话。

### 4.3 新建会话（本地草稿）

1. 点侧栏顶部「＋ 新对话」；若当前有流式回答，先 `abort`。
2. 不调用任何接口，直接进入草稿态：`activeConversationId = null`，清空对话区与检索面板，主区显示"新会话（未保存）"。
3. **「最近」分组不新增任何条目**；草稿不会出现在侧栏，也不占位。
4. 若当前已是草稿态，再次点击视为清空当前草稿并保持草稿态。
5. 草稿态不写 localStorage；切走或刷新即丢弃。

### 4.4 删除会话

1. 仅「最近」中带 `conversationId` 的真实会话在 **hover 时**显示小删除图标；草稿态不在列表中占位，因此没有删除入口，也不调用删除接口。
2. 点击删除图标，复用现有 `ConfirmDialog` 二次确认（删除文案带会话标题）。
3. 若删除的是当前会话且有流式回答，先 `abort`。
4. 调 `DELETE /api/conversations/{id}`。
5. 成功后从列表移除；若删除的是当前会话，切换到列表中最近的会话（并加载其历史），列表为空则回到空态。
6. 失败按 2.6 处理。

### 4.5 切换会话

1. 若当前是草稿态：直接丢弃草稿，不调用任何接口。
2. 若当前有流式回答，先 `abort`（以新会话为准，不等待旧流收尾）。
3. 设置当前 `conversationId`，清空上一会话的本地消息与检索面板。
4. 调 `GET .../messages`，按 `id ASC` 顺序渲染。
5. 加载中展示骨架/加载态；失败展示错误与重试，不展示上一会话残留数据。

### 4.6 历史消息渲染

- 历史消息按 `roundId` 分组渲染每一轮：组内 `USER` 为右侧提问，`ASSISTANT` 为左侧回答与引用；不得用数组顺序配对。
- `USER` 消息：右侧用户气泡，正文原样展示。
- `ASSISTANT` 消息：
  - `DONE`：复用 `AnswerBody` 渲染正文与【来源N】引用；点击引用时切换右侧检索面板到该轮来源，并高亮对应条目。
  - `FAILED`：正文（若有）+ 错误条 + "重试"按钮（重试即以同一问题在当前会话重新提问，使用服务端 `conversationId`）。
  - `STOPPED`：正文（若有）+ "已停止"标签。
- 每轮右侧检索面板展示该轮的 `sources` 与 `provider / elapsedMillis / sourceCount`（从消息字段还原为 `QaDone` 展示）。
- 历史消息不参与流式状态；只有当前进行中的一轮显示"正在检索/正在引用 N 份资料/停止"。
- 历史消息与重试只能使用服务端 `messageId` / `conversationId`，不得使用临时 key。

### 4.7 提问与流式

1. 草稿态与真实会话都允许提问；草稿态首问请求体**不包含** `conversationId`，真实会话请求体包含。
2. 输入框在 `streaming` 期间禁用。
3. 发送时立即创建本地助手气泡，使用临时 key `tmp-<seq>`；`Turn.messageId` 初始为 `null`。
4. 收到 `meta`：
   - 把 `activeConversationId` 设为 `meta.conversationId`（草稿态转正）；
   - 把当前助手气泡的临时 key 替换为 `meta.assistantMessageId`；
   - 记录 `meta.userMessageId` 供后续定位；
   - 立即刷新会话列表：新会话插入「最近」分组**最前面**并高亮为当前会话，标题已是首问前 20 字符；不得等 `done` 才出现。
5. 收到 `sources`：更新该轮来源列表。
6. 收到 `delta`：增量拼接正文。
7. 收到 `done`：本轮标记完成，记录 `provider / elapsedMillis / sourceCount`；再次刷新列表以更新排序与 `updateTime`。
8. 收到 `error`：标记失败，展示 `error` 文本，保留已生成内容。
9. 用户停止：标记停止，保留已生成内容。
10. 若流在收到 `meta` 前就失败（超时/网络错误）：会话保持草稿态、无 id；用户重发会创建新会话，见 1.3 已知限制。

### 4.8 404 / 409 处理

- 404：仅可能发生在已有会话；移除会话、提示、切换到最近会话或空态；不得继续向该 `conversationId` 发送请求。
- 409：仅可能发生在已有会话；在对话区内展示提示；把上一条问题恢复到输入框；不得自动重试。
- 400：展示服务端 `msg`，保留输入内容。

## 5. 改动面与代码位置

### 5.1 用户标识常量

- 新增 `src/lib/identity.ts`：

```ts
/** 开发期固定用户标识；接入登录后仅替换此处的来源 */
export const DEV_USER_ID = 'dev-user-001';
```

- `src/api/client.ts` 增加统一的请求头构造（如 `authHeaders()`），`request()` 与 SSE 的 `fetch` 都必须使用它注入 `X-User-Id`。
- 除 `identity.ts` 外，任何文件不得出现字面量 `dev-user-001`。

### 5.2 API 封装

- 新增 `src/api/conversations.ts`（**无 create 方法**）：
  - `listConversations(): Promise<ConversationSummary[]>`
  - `deleteConversation(conversationId: string): Promise<{ conversationId: string; deleted: true }>`
  - `listMessages(conversationId: string): Promise<MessageList>`
- 修改 `src/api/qa.ts`：`streamQa(conversationId: string | null, question, handlers, signal)`；`conversationId` 为 `null` 时请求体省略该字段。`QaHandlers` 增加 `onMeta(meta: QaMeta)`。

```ts
export interface QaMeta {
  conversationId: string;
  roundId: string;
  userMessageId: string;
  assistantMessageId: string;
}
```

- 修改 `src/api/types.ts`：新增 `ConversationSummary`、`MessageItem`、`MessageList`、`MessageRole`、`MessageStatus`、`QaSourceItem`、`QaMeta`；保留现有 `QaSource` / `QaDone` 供展示层复用（字段一致）。
- 修改 `src/api/sse.ts`：无需改动解析逻辑，`meta` 作为普通事件由 `qa.ts` 分发。

### 5.3 状态与组件

- 当前会话状态使用 `activeConversationId: string | null`：`null` 即草稿态，不使用额外布尔标志。
- 新增 `src/features/chat/ConversationSidebar.tsx`：列表、新建入口、真实会话删除入口、当前项高亮、折叠态；草稿态不渲染列表项。
- 新增 `src/features/chat/useConversations.ts`：列表加载、删除、当前会话切换、列表刷新；不提供 create。
- 修改 `src/features/chat/useQaStream.ts`：
  - 入参 `conversationId: string | null`；
  - `Turn` 增加 `messageId: string | null`、`roundId: string | null` 与本地渲染 key（如 `localKey`）；
  - 处理 `onMeta`：替换临时 key 为 `assistantMessageId`，回调上层完成草稿转正；
  - 提供从 `MessageItem[]` 还原历史 `Turn[]` 的能力（或由 `ChatPage` 直接渲染历史）。
- 修改 `src/features/chat/ChatPage.tsx`：接入侧栏、草稿态、当前会话、历史加载、切换/删除编排；空态文案去掉"单轮"。
- 修改 `src/features/chat/Thread.tsx`：支持历史消息的 `FAILED` / `STOPPED` 标签与重试；复用 `AnswerBody` 与引用点击。
- 修改 `src/components/AppShell.tsx`：无新增导航项；对话页内部布局变化，不改左侧主导航。

### 5.4 mock 模式

- `src/api/mock.ts` 增加内存态会话仓库，按懒创建语义实现：
  - 首次 `streamQaMock(conversationId = null, ...)` 时创建内存会话、生成 id 与 title（首问前 20 字符）；
  - 先回调 `onMeta`（含 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`），再回调 `sources` / `delta` / `done`；
  - 列表、删除、历史消息按 2.1 ~ 2.5 的契约返回；
  - mock 会话仅存在内存，刷新即失效，不影响真实链路。
- `isMockEnabled()` 判断逻辑保持不变。

## 6. 可测试的验收标准

### 6.1 草稿态与懒创建

1. 首次进入且无会话时展示空态，不自动创建会话。
2. 点击侧栏顶部「＋ 新对话」后：进入草稿态（无 `conversationId`），主区显示"新会话（未保存）"，「最近」分组不新增任何项。
3. 在草稿态不提问直接切走/刷新：会话列表仍无新记录，服务端不产生会话。
4. 草稿态不显示删除图标，任何操作都不会调用 `DELETE /api/conversations/*`。
5. 连续进入两个草稿并各自提问，两条会话的 `conversationId` 不同，历史互不串。

### 6.2 首问与 meta

6. 草稿态首问的请求体不包含 `conversationId`；收到 `meta` 后，`activeConversationId` 等于 `meta.conversationId`。
7. 首问成功后，会话列表出现该会话，标题等于首问前 20 个字符；刷新页面后标题与历史仍在。
8. 再次提问的请求体包含上一步得到的 `conversationId`，且 `meta.conversationId` 与之一致。
9. SSE 首事件为 `meta`，载荷同时包含 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`。

### 6.3 临时 key 与消息定位

10. 流式期间助手气泡使用 `tmp-<seq>` 作为渲染 key；收到 `meta` 后该 key 被 `assistantMessageId` 替换。
11. 历史消息渲染、失败重试、引用定位使用的都是服务端 `messageId` / `conversationId`，切换到历史会话后仍可准确定位。
12. 刷新页面后，历史消息的 `messageId` 与流式当时 `meta.assistantMessageId` 一致。

### 6.4 删除与切换

13. 删除真实会话需二次确认；确认后该项从侧栏消失，刷新后不再出现。
14. 删除当前会话后，自动切换到列表中最近的会话；若列表为空则回到空态。
15. 切换会话时，若上一会话正在流式输出，旧请求被中止且新会话不出现旧内容残留。
16. 切换会话后历史消息按时间正序渲染，`USER` 在右、`ASSISTANT` 在左。

### 6.5 历史渲染与轮次分组

17. 历史助手消息正文中的【来源N】可点击，右侧检索面板展示该轮的 `sources`、`provider`、耗时与引用数。
18. `sources` 为 `[]` 的历史轮次展示"未召回"提示，与现有单轮行为一致。
19. `FAILED` 历史消息展示错误与"重试"；`STOPPED` 展示"已停止"标签；两者正文均保留已生成部分。
20. 历史消息不再显示"正在检索/回答生成中"的流式状态。
21. `GET .../messages` 返回扁平数组；前端按 `roundId` 分组后，每组恰好一条 `USER` + 一条 `ASSISTANT`，提问在右、回答在左。
22. 存在 `FAILED` / `STOPPED` 轮次时，其用户消息与助手消息仍按同一 `roundId` 聚成一轮展示，不被拆散。
23. 即使两条消息 `createTime` 相同或接口顺序异常，渲染仍正确（配对只依赖 `roundId`，不依赖数组下标）。
24. 重试 `FAILED` 轮次时，新请求生成新的 `roundId`，旧轮保持原样展示。

### 6.6 流式与错误

25. 所有请求头携带 `X-User-Id: dev-user-001`（可用浏览器 Network 面板核对），草稿态首问也不例外。
26. 收到 409 时展示"该会话正在生成回答，请稍后"，并将原问题恢复到输入框，不自动重试。
27. 收到 404 时移除该会话并提示，后续不再向该 id 发请求。
28. 用户点击停止后，正文保留并显示"已停止"；再次提问可正常开始。
29. **已知限制用例**：模拟首问在收到 `meta` 前超时，用户重发（仍是草稿态、不带 id），列表出现两条会话；按 1.3 接受此行为，不做去重。
30. mock 模式下（`?mock=1`）草稿、首问懒创建、切换、删除、多轮问答全流程可用。

### 6.7 布局

31. 会话侧栏、对话区、检索面板在 1440px 宽度下无横向溢出；窄屏会话侧栏可折叠。
32. 草稿态主区的"未保存"标识可见，且不会在侧栏产生悬空项。
33. 现有文档管理页不受影响，导航与知识库概览正常。

### 6.8 侧栏结构

34. 侧栏顶部固定为「＋ 新对话」；其下是「最近」分组；不出现「项目」分组的标题、占位或禁用项。
35. 空态时主区是欢迎语 + 可直接使用的输入框，不是空白列表面板；侧栏「最近」分组保留标题但无条目。
36. 点「＋ 新对话」后「最近」分组条目数不变；首问收到 `meta` 后，新会话插到「最近」最前面并高亮。
37. hover 真实会话条目才出现小删除图标，移出即消失；草稿态没有任何删除入口。
38. 两个会话先后首问成功，后创建的排在「最近」最前；顺序与 `updateTime DESC` 一致。
39. 删除「最近」中某条后顺序立即重排；删除当前会话按 4.4 切换。

## 7. 跨端契约：后端必须遵守的部分

1. 后端**不提供** `POST /api/conversations`；会话在首问受理时创建。
2. `conversationId`、`messageId` 均由后端生成 UUID v4；首问前不要求前端持有 id。
3. 所有会话接口与 `/api/qa` 必须接受并校验 `X-User-Id`；缺失/非法返回 400。
4. `/api/qa` 的 `conversationId` 必须可选：缺省即懒创建，且新路径不得返回 404 / 409。
5. 成功 SSE 的第一个事件必须是 `meta`，固定包含 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`。
6. `done` 载荷保持 `{provider, elapsedMillis, sourceCount}` 三字段，不新增字段。
7. 会话列表只返回当前用户的 `ACTIVE` 会话，按 `updateTime` 倒序，不分页。
8. 删除为软删；携带已有 id 的访问在不存在/已删/非本人时返回 404 `CONVERSATION_NOT_FOUND`。
9. 已有会话并发问答返回 409 `CONVERSATION_BUSY`；409 必须发生在 SSE 流建立之前（普通 JSON）。
10. 历史消息接口按时间正序返回，字段与 2.5 完全一致；`ASSISTANT` 的 `sources` 必须持久化且形状与 SSE 一致，未召回为 `[]`。
11. 首问创建会话时标题即取首问前 20 个字符；`createTime` / `updateTime` 使用 `YYYY-MM-DD HH:mm:ss`。
12. 只有完整 `DONE` 轮次进入模型上下文；`FAILED` / `STOPPED` 仅作为历史展示。
13. 客户端停止后，服务端必须落库 `STOPPED` 并保留已生成正文，且释放并发锁。
14. 首问超时重试会产生重复会话（1.3 已知限制），后端不做幂等合并。
15. 后端必须保证同一轮 USER / ASSISTANT 共享同一 `roundId`，历史接口每条消息都带 `roundId`；前端按 `roundId` 分组渲染。

## 8. 分阶段实施顺序（★ 为可并行）

| 阶段 | 前端 | 后端 | 并行关系 |
|---|---|---|---|
| P0 | 本文档定稿 | 后端文档定稿 | 已完成 |
| P1 | `types.ts`、`identity.ts`、请求头注入 | schema、ErrorCode、枚举、`UserIdInterceptor` | ★ 前后端可同时开工 |
| P2 | 会话侧栏、草稿态、切换/删除、历史加载与渲染 | 会话列表、软删、历史消息接口 | ★ P2 阶段双方并行，依赖 P1 契约 |
| P3 | 首问流程、`meta` 解析、临时 key 替换、草稿转正 | 懒创建、`meta` 四字段、带 history 的策略签名、落库与锁 | ★ P2 完成后双方并行开发，联调放在 P4 |
| P4 | 联调：懒创建、SSE 全序列、停止、409、404、标题刷新 | 同左 | 联调，不并行 |
| P5 | mock 模式补齐、跨端回归 | 删除 `static/index.html`、`mvn clean test` | 可并行 |
