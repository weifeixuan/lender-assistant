# 04: 会话软删除

**What to build:** 用户在侧栏 hover 会话并确认后，调 `DELETE /api/conversations/{conversationId}` 删除会话；删除后会话立即从列表消失、历史打不开，但消息数据仍保留在库中。重复删除与删除他人会话都表现为"不存在"。

**涉及层次:** conversation 的 controller/service/mapper、MockMvc 与仓储测试。

**Blocked by:** 02, 03

**Status:** ready-for-agent

**Estimate:** 1 小时

- [ ] 删除成功返回 `200` + `{conversationId, deleted: true}`
- [ ] 会话 `status` 由 `ACTIVE` 变为 `DELETED`，`update_time` 刷新；message 行不被删除
- [ ] 删除后 `GET /api/conversations` 不再返回该会话
- [ ] 删除后 `GET /api/conversations/{id}/messages` 返回 `404 CONVERSATION_NOT_FOUND`
- [ ] 重复删除、删除他人会话、删除不存在的会话均返回 `404 CONVERSATION_NOT_FOUND`
- [ ] 删除操作在单个事务内完成
- [ ] 测试断言软删后列表与历史均不可见，且库中消息条数不变
