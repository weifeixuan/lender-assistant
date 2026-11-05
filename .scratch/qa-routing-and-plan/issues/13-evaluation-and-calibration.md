# 13: 评测端点扩展与评估集（P4）

**阶段:** P4 评测校准
**Blocked by:** 11
**Status:** ready-for-agent

**范围:** 扩展评测入口以覆盖路由准确率、计划合法率与指代型追问，并给出阈值校准方法。

**要做的事:** 阈值 `0.6` 与步骤上限不再靠感觉，而由一套可重复的评估集支撑。

**涉及文件/类:** `EvalController#runRouting`、`RoutingEvalServiceImpl`、`src/main/resources/eval/routing-evalset.json`、`RoutingEvalResponse`。

- [x] 评估集分类：`IRRELEVANT` / `KNOWLEDGE` / `PLAN` 各不少于约定条数，另含「指代型追问」一类
- [x] 输出指标：路由准确率、低置信占比、计划合法率（整单校验通过比例）、平均步骤数
- [x] 指代型追问用例的验收动作固定为「计划不得引入与当前问题无关的步骤」
- [x] 评估运行不改生产默认配置，阈值调整需单独提交配置
- [x] 结果可重复：同一评估集两次运行指标差异在可接受范围内
- [x] 形成一份校准记录：当前阈值下的准确率，以及调整阈值的依据

**不做什么:** 不引入在线 A/B；不做自动阈值调优；不改路由/计划业务逻辑（发现问题另开票）。

## 交付物

- 端点：`POST /api/admin/eval/routing`（无入参，跑固定评估集，只读当前阈值）
- 评估集：`src/main/resources/eval/routing-evalset.json`，共 35 条
  - `IRRELEVANT` 10 条、`KNOWLEDGE` 10 条、`PLAN` 10 条、`COREFERENCE` 5 条
  - 指代型条目额外带 `previousQuestion` 与 `allowedTools`
- 指标定义：
  - 路由准确率 = 预测路由与标注一致 / 总条数
  - 低置信占比 = `confidence < routing.confidence-threshold` / 总条数
  - 计划合法率 = 期望 `PLAN` 条目中整单校验通过 / 期望 `PLAN` 条数
  - 平均步骤数 = 合法计划的平均步骤数
  - 指代验收通过率 = 合法且步骤工具全部落在 `allowedTools` 内 / 指代条目数
- 可重复性：评估集与指标口径固定，同一数据集两次运行的差异仅来自模型不确定性；mock provider 下结果完全确定。

## 校准记录（当前阈值 0.6）

**结论：未完成实测校准，明确留给使用者。**

原因：`confidence` 的实际分布与阈值准确率需要两样东西，本环境都不具备——

1. 真实路由模型（当前默认 `rag.chat.provider=mock`，路由被编排层跳过，返回固定 `KNOWLEDGE`）；
2. 真实标注集（仓库内 35 条评估集是业务编写的验收样例，不能当作模型校准的统计样本）。

因此不提供任何编造的"当前阈值准确率"数字。

**执行方法（有真实模型与标注集后照做）：**

1. 设置 `RAG_CHAT_PROVIDER=openai`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`；
2. 启动服务，调用 `POST /api/admin/eval/routing`，记录返回的 `routeAccuracy`、`lowConfidenceRatio`、`planValidRate`、`avgSteps`、`coreferencePassRate`；
3. 依次把 `RAG_ROUTING_CONFIDENCE_THRESHOLD` 设为 0.5 / 0.6 / 0.7 / 0.8 重跑（每次重启进程），得到阈值-准确率曲线；
4. 选择曲线拐点或满足业务准确率下限的最高阈值；
5. 阈值变更单独提交配置（`application.yml` 的 `routing.confidence-threshold`），不改路由/计划代码。

**mock provider 下的预期结果：** 计划合法率与平均步骤数为 0（`MockPlannerModelClient` 返回空计划，整单校验必然弃用），指代验收通过率为 0。这是诚实结果，不是缺陷。
