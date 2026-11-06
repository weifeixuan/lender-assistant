# 03: route 事件、无关轮落库与回放排除 [待确认]

**阶段:** P1 路由
**Blocked by:** 02、01
**Status:** ready-for-agent
**[待确认]:** 无关轮是否排除历史回放——当前按「排除」实现，若改为「保留」需回退本节第 5 条

**范围:** 把路由接入 `POST /api/qa` 编排，新增 `route` 事件与 `IRRELEVANT` 分支；mock 模式旁路。

**要做的事:** 用户发出问题后，先看到本轮类型；无关问题立刻得到固定提示；该轮以 `provider="rule"` 落库且不影响后续追问。

**涉及文件/类:** `QaServiceImpl`、`QaRouteResponse`（SSE 载荷）、`ConversationHistoryAssembler`、`rag.routing.irrelevant-reply`。

- [ ] 事件序列：`meta → route → …`，`route` 恰发一次，载荷 `{route, confidence}`
- [ ] `IRRELEVANT`：不发检索、不调生成；事件为 `meta → route → sources([]) → delta(固定提示) → done`
- [ ] `IRRELEVANT` 轮消息 `DONE`、`sources=[]`、`done.provider="rule"`、`sourceCount=0`
- [ ] **`[待确认]`** `provider="rule"` 的轮次整轮跳过历史回放，也不作为 `previousQuestion` 来源；该轮仍出现在历史消息接口
- [ ] `rag.chat.provider=mock` 时跳过路由与计划，事件序列 `meta → sources → delta* → done`，不发 `route` / `plan` / `step`
- [ ] 整合测试：三分类各一条事件序列断言；mock 模式断言无新事件

**不做什么:** 不做计划生成；不改前端；不新增端点；不为 mock 伪造事件。
