# 07: 助手消息的异常与中断终态

**What to build:** 助手回答不只会在正常结束时落库：模型失败、用户主动停止、连接断开、以及流式过程中会话被删除，都有确定的落库行为。历史接口能如实展示 `FAILED` / `STOPPED` 的部分回答，这些轮次不会污染模型上下文。

**涉及层次:** SSE 生命周期（onTimeout / onError / onCompletion）、QA 编排、message 的 service 与 mapper、服务层单测。

**Blocked by:** 04, 06

**Status:** ready-for-agent

**Estimate:** 2 小时

- [ ] 模型异常：推送 `error` 事件后结束流，助手消息以 `FAILED` 落库，保留已生成正文，`error` 字段非空
- [ ] 用户主动停止或客户端断开：助手消息以 `STOPPED` 落库，保留已生成正文，不再尝试发送 `error`
- [ ] `STOPPED` / `FAILED` 消息仍写入本轮 `roundId`，历史接口可读
- [ ] 未召回时：`sources` 事件为空数组，仍推送固定提示的 `delta` 与 `done`，助手消息 `sources` 写 `[]`
- [ ] 流式过程中会话被删除：当前轮允许跑完，但结束时跳过助手消息落库，且不刷新该会话 `update_time`
- [ ] 助手消息落库后刷新会话 `update_time`，列表排序随之后移
- [ ] 服务层单测覆盖 `DONE` / `FAILED` / `STOPPED` 三种终态与"删除中跳过落库"
