# 03: 历史消息读取

**What to build:** 点击侧栏任一会话后，前端可以通过 `GET /api/conversations/{conversationId}/messages` 取回该会话的全部历史消息，按发生顺序扁平返回，每条都带 `roundId`，并能从 `sources` 还原引用来源。他人会话、已删除会话、不存在的会话统一表现得像"不存在"。

**涉及层次:** message 的 controller/service/mapper、消息响应模型、MockMvc 与仓储测试。

**Blocked by:** 01, 02

**Status:** ready-for-agent

**Estimate:** 1.5–2 小时

- [ ] 合法请求返回 `200` + `{conversationId, messages}`，`messages` 按 `id ASC`
- [ ] 每条消息含 `messageId`、`roundId`、`role`、`content`、`status`、`sources`、`provider`、`elapsedMillis`、`error`、`createTime`
- [ ] 同一轮 USER 与 ASSISTANT 的 `roundId` 相同；不同轮次不同
- [ ] USER 消息的 `sources` / `provider` / `elapsedMillis` / `error` 为 `null`
- [ ] ASSISTANT 未召回时 `sources` 为 `[]`，不省略字段
- [ ] 空会话返回 `200` + `messages: []`
- [ ] 不存在、已软删、属于其他 `userId` 的会话一律返回 `404 CONVERSATION_NOT_FOUND`
- [ ] 同一秒内写入的多条消息顺序仍稳定（按 `id` 而非 `create_time` 排序）
- [ ] MockMvc 测试覆盖成功、空会话、三种 404 场景
