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
- [ ] 流式过程中会话被删除：当前轮允许跑完，助手消息照常落库（消息与会话无级联关系），但不刷新该会话 `update_time`
- [ ] 助手消息落库后刷新会话 `update_time`，列表排序随之后移
- [ ] 服务层单测覆盖 `DONE` / `FAILED` / `STOPPED` 三种终态与"会话删除后仍落库"

## Comments

### 2026-11-03 `FAILED` 演练触发通路（现状评估结论：复用既有开关，不新增代码）

**为什么归到 07**：`FAILED` 终态、`error` 事件与历史回放都是本票的产出；本次只补"怎么确定性地
触发它"的演练通路，没有改终态语义。

**现状盘点（可用手段，均为既有实现）**

| 手段 | 配置 | 位置 | 实际效果 |
|---|---|---|---|
| chaos 故障注入 | `rag.resilience.chaos=true`（环境变量 `RAG_RESILIENCE_CHAOS`），默认 `false` | [ResilientLlmExecutor](../../src/main/java/com/lender/assistant/resilience/ResilientLlmExecutor.java#L70-L83) | 每次经执行器的模型调用都抛 `IllegalStateException`，量化场景可确定性触发 `FAILED` |
| 熔断 | `rag.resilience.failure-threshold` / `wait-duration-sec` | 同上 | 连续 3 次失败进入 OPEN，之后请求快速失败（走 `CallNotPermittedException` 分支，同样落 `FAILED`） |
| 重试 | `rag.resilience.max-attempts` | 同上 | 问答 `allowRetry=false`（SSE 不可重放），不参与失败演练 |
| 测试专用 profile | 无 | — | 仓库没有 `application-dev.yml` 一类的测试 profile |

结论：已有可用开关，按约定**不新增** `app.chaos.fail-chat` 之类的新配置。

**触发命令（本地）**

```powershell
$env:RAG_RESILIENCE_CHAOS = "true"
java -jar target/lender-assistant-0.1.0-SNAPSHOT.jar
```

```bash
curl -N -X POST http://localhost:8080/api/qa \
  -H "Content-Type: application/json" -H "X-User-Id: dev-user-001" \
  -d '{"conversationId":null,"question":"单笔代收接口的限额是多少","topK":4}'
```

**实测结果（Mock 模型 + 真 PG）**

- SSE：`meta` → `sources` → `delta*`（完整 Mock 回答）→ `error`
  `data:"问答失败：chaos: 模拟模型故障（rag.resilience.chaos=true）"`；
- DB：该轮 `USER(DONE)` + `ASSISTANT(FAILED)`，助手正文 570 字符（已生成内容保留），
  `error_msg` 与 SSE 的 error 文案一致；
- 历史：`GET /api/conversations/{id}/messages` 返回该轮两条消息，助手 `status="FAILED"`、
  `error` 非空、`sources` 完整，前端可据此渲染 `FAILED` 分支。

**已知差异（如实记录，未改动）**

- chaos 抛错发生在 `call.get()` 之后（"调用已发出但失败"的既有语义），所以失败是**回答流完之后**才到达；
  前端会先看到完整 Mock 回答再看到错误。若要"首 token 前失败"，需另加开关或调整 chaos 语义，本次未做。
- chaos 是全局开关：同时影响抽取/草稿等经执行器的调用，演练需单独起进程，不能与正常联调并行。

