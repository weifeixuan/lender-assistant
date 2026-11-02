# 05: 首问懒创建（核心 tracer bullet）

**What to build:** 用户在草稿态发出第一个问题时，后端不要求前端提供会话 id，而是受理时创建会话、生成会话 id / 轮次 id / 两个消息 id，并在流的最前面用 `meta` 事件回传；随后按原协议推送来源与增量回答。会话随即出现在会话列表最前面，标题由首问正文前 20 个字符生成。正常结束后本轮用户消息与助手消息都以 `DONE` 落库并共享同一个 `roundId`。

**涉及层次:** HTTP 请求体与拦截器、QA 编排、conversation/message 的 service 与 mapper、SSE 事件、MockMvc 测试。

**Blocked by:** 01, 02

**Status:** ready-for-agent

**Estimate:** 2 小时

- [ ] 不带 `conversationId`（或为 `null`）的 `POST /api/qa` 返回 SSE 流，HTTP 状态 200
- [ ] 首个 SSE 事件名为 `meta`，载荷含 `conversationId`、`roundId`、`userMessageId`、`assistantMessageId`，四项均为 UUID
- [ ] `meta` 之后事件顺序为 `sources → delta* → done`，事件名与现有协议一致
- [ ] 库中新增 1 条 `ACTIVE` 会话，标题为去首尾空白后的前 20 个 Unicode code point
- [ ] 库中新增 1 条 USER 消息（`DONE`）与 1 条 ASSISTANT 消息（`DONE`），两者 `roundId` 相同，且与 `meta` 回传值一致
- [ ] 助手消息的 `sources` 与本次 `sources` 事件一致，`provider` 与 `elapsedMillis` 有值
- [ ] 用户消息与助手消息落库后刷新会话 `update_time`；`GET /api/conversations` 中该会话排第一
- [ ] 新建路径不返回 404 / 409；`question` 空白仍返回 `400 PARAM_INVALID`
- [ ] `done` 载荷保持 `provider`、`elapsedMillis`、`sourceCount` 三字段不变
- [ ] MockMvc 测试断言 `meta` 是首事件、`meta` 与库中消息 id 一致、列表可见
