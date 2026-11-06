# 04: AgentTool / ToolResult / PlanToolRegistry 与 kb.search

**阶段:** P2 计划与工具
**Blocked by:** None (can start immediately)
**Status:** ready-for-agent

**范围:** 工具抽象三件套 + 第一个真实工具 `kb.search`。不涉计划生成。

**要做的事:** 执行层可以用名字拿到工具并做一次确定性调用，检索能力以工具形态被计划复用，且不新增检索实现。

**涉及文件/类:** `rag/tool/AgentTool`、`ToolResult`、`PlanToolRegistry`、`rag/tool/impl/KbSearchPlanTool`、`SearchService`。

- [ ] `AgentTool` 只有 `name()` 与 `call(args, ctx)`；`ToolResult` 含 `ok` / `content` / `chunkIds` / `errorCode`
- [ ] `PlanToolRegistry` 由 `Map<String, AgentTool>` 构建，`get(name)` 命中返回工具、未命中返回空
- [ ] `kb.search` 调用 `hybridSearch(args.query, null, args.topK)`，执行时 `previousQuestion` 固定为 `null`
- [ ] `args` 校验：`query` 必填且 ≤200 字；`topK` 缺省时取默认、显式值须在 1–20
- [ ] 返回 `chunkIds` 为知识库块 id 有序列表；零命中时 `ok=true` 但内容标注「资料中未提及」，不抛异常
- [ ] 单测：未注册工具、`args` 非法、正常命中、零命中；不调用模型

**不做什么:** 不新增检索算法；不做工具自动发现；不引入 Factory；不实现执行编排。
