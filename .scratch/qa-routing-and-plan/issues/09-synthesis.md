# 09: 汇总合成（唯一生成调用）

**阶段:** P3 失败与预算
**Blocked by:** 07、08
**Status:** ready-for-agent

**范围:** 计划末尾的一次流式汇总生成，复用现有 `AnswerGeneratorStrategy`。新增 `LlmScene.SYNTHESIS`。

**要做的事:** 用户最终看到一段完整回答，它基于 `goal` 与全部步骤产物，并明确说明缺失信息；全程只有这一次模型生成。

**涉及文件/类:** `PlanAndExecuteService`（合成）、`AnswerGeneratorStrategy`、`LlmScene.SYNTHESIS`、合成 Prompt。

- [ ] `PLAN` 轮只有一次流式生成调用，`PLAN` 轮 LLM 调用总计恒为 3 次（路由 + 计划 + 合成）
- [ ] 合成输入只用 `goal` + 步骤产物 + 全局来源，不带完整多轮历史
- [ ] 产物总长超过 6000 字符时截断，并置 `execution_plan.contextTruncated=true`
- [ ] 失败/降级步骤以「sN 未召回」「sN 参数非法」告知模型，要求回答明确说明缺口，不编造
- [ ] 所有 `kb.search` 零命中且无业务产物 → 沿用「资料库中没有检索到相关内容」，`sourceCount=0`
- [ ] 合成超时取 `min(现有问答流超时 60s, 剩余预算)`，剩余预算由票 10 提供
- [ ] `delta` 只在此阶段产生；`done` 三字段不变
- [ ] 单测：截断标记、缺步说明、零命中提示、只有一次生成调用

**不做什么:** 不做多轮合成；不做流式重试；不改生成策略接口；不新增模型供应商。
