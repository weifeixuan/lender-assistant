# 06: 既有会话追问与同会话并发保护

**What to build:** 用户带着已有 `conversationId` 追问时，后端校验会话存在、未删除且属于当前用户，然后在同一会话下追加新的一轮；同一会话同时只允许一个回答在生成，第二次并发提问得到明确的忙碌提示而不是把历史写乱。不同会话可以同时生成。

**涉及层次:** QA 请求校验、conversation 归属查询、进程内会话锁、conversation/message 的 service 与 mapper、MockMvc 测试。

**Blocked by:** 05

**Status:** ready-for-agent

**Estimate:** 1.5–2 小时

- [ ] 携带合法 `conversationId` 追问返回 SSE，`meta.conversationId` 与请求一致，`meta.roundId` 与上一轮不同
- [ ] 新轮次写入 1 条 USER 消息（`DONE`）与 1 条 ASSISTANT 消息（`DONE`），共享新 `roundId`
- [ ] 会话不存在、已软删、属于其他 `userId` 时返回 `404 CONVERSATION_NOT_FOUND`（普通 JSON，不建立 SSE）
- [ ] `conversationId` 非 UUID 时返回 `400 PARAM_INVALID`
- [ ] 同一会话已有流式问答进行中时，第二次请求返回 `409 CONVERSATION_BUSY`，且不写入任何消息
- [ ] 上一轮结束（成功或失败）后锁被释放，同一会话可再次提问
- [ ] 两个不同会话的请求可以并行生成，互不返回 409
- [ ] 请求线程在写用户消息前取锁，工作线程在 `finally` 释放锁；测试覆盖释放路径
- [ ] MockMvc / 服务层测试覆盖 404、400、409、锁释放四类场景
