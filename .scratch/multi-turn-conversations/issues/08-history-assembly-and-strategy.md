# 08: 历史装配与生成策略签名

**What to build:** 追问时模型能真正"记得"之前的对话：后端按 `roundId` 取出最近若干个完整且都成功的轮次，整轮裁剪到字符上限，按时间正序传给模型；失败或停止的轮次只展示不回放。检索 query 也会带上最近一条历史用户提问，让"那二套呢"这类指代能检索到正确资料。

**涉及层次:** message 的历史查询、历史装配服务、`AnswerGeneratorStrategy` 接口与两个实现、检索 query 构造、配置项、单测。

**Blocked by:** 03, 07

**Status:** ready-for-agent

**Estimate:** 2 小时

- [ ] `AnswerGeneratorStrategy` 新签名接收有序历史（`role` + `content`）、当前问题与召回结果，两个实现同步适配
- [ ] OpenAI 兼容实现按顺序把历史映射为对话消息，最后追加当前用户消息；Mock 实现签名一致并忽略历史
- [ ] 历史按 `roundId` 分组，每组必须同时有一条 USER 和一条 ASSISTANT 且状态均为 `DONE`；缺任一条整轮丢弃
- [ ] 最多回放最近 6 轮；总字符超 2000 时从最旧一轮开始整轮丢弃
- [ ] `FAILED` / `STOPPED` 轮次不进入上下文
- [ ] 新建会话历史为空，首问行为不变
- [ ] 检索 query 为「最近一条历史 USER 正文 + 换行 + 当前问题」；无可用历史时只用当前问题
- [ ] `rag.qa.history-max-rounds` 与 `rag.qa.history-max-chars` 可配置并生效
- [ ] 单测覆盖 6 轮上限、2000 字符整轮裁剪、非 `DONE` 剔除、缺一条剔除、query 拼接
