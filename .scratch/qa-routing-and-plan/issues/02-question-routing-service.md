# 02: 路由服务（结构化分类与降级）

**阶段:** P1 路由
**Blocked by:** 01（需要 `message.route` 供 `previousRoute` 读取）
**Status:** ready-for-agent

**范围:** 新增路由服务与路由 Prompt，新增 `LlmScene.ROUTE`。不接 SSE 编排（由票 03 接入）。

**要做的事:** 每轮对当前问题做一次非流式结构化判定，输出三分类与置信度；任何异常都降级为 `KNOWLEDGE`，不让分类失败变成用户可见错误。

**涉及文件/类:** `QuestionRoutingService` + `Impl`、`LlmScene.ROUTE`、路由 Prompt 资源、`ResilientLlmExecutor` 的非流式调用路径。

- [ ] 输出 JSON 含 `route` / `confidence` / `reason` / `irrelevantCategory` 四字段，`reason` 只落库与日志
- [ ] 路由值不在三分类内、JSON 非法、调用超时、熔断、`confidence < rag.routing.confidence-threshold`（默认 0.6）→ 返回 `KNOWLEDGE`，不抛异常
- [ ] 路由输入含当前问题、上一轮用户问题、上一轮 `route`、知识库目录；目录仅含 `docId` + `docTitle`，不含正文
- [ ] `previousRoute` 只取最近一条 `DONE` 助手消息；最近一条为 `FAILED` / `STOPPED` 时视为无 `previousRoute`，不向前回溯
- [ ] 追问保护：判 `IRRELEVANT` 但 `previousRoute` 是 `KNOWLEDGE` / `PLAN` → 强制 `KNOWLEDGE`
- [ ] 单次调用受 `NON_STREAM_CALL_TIMEOUT`（10s）约束，失败沿用 `max-attempts=2`（重试 1 次）
- [ ] 单测覆盖：非法 JSON、超时、低置信、未知路由值、追问保护、`previousRoute` 缺失

**不做什么:** 不改检索与生成；不加路由缓存；不发 SSE 事件；不引入 `Strategy`。
