# 09: 流任务提交失败与断连兜底

**What to build:** 当流式线程池饱和、任务提交失败、流超时或发送时发现客户端已断开，系统不会留下面无交代的会话：要么明确返回 500 并留下 `FAILED` 记录，要么按 `STOPPED` 结束并释放会话锁，用户和后续请求都不会被卡住。

**涉及层次:** QA 编排的线程池提交路径、SSE 生命周期回调、会话锁释放、message 落库兜底、单测。

**Blocked by:** 05, 07

**Status:** ready-for-agent

**Estimate:** 1.5 小时

- [ ] 线程池拒绝任务时，请求得到 `500 INTERNAL_ERROR` 普通 JSON，不挂起、不返回半开 SSE
- [ ] 提交失败时仍落 1 条 `FAILED` 助手消息（正文为空、错误说明非空），并与本轮 USER 消息共享 `roundId`
- [ ] 拒绝或提交异常路径释放会话锁，同一会话可立即再次提问
- [ ] `SseEmitter` 超时按客户端断开处理：助手消息落 `STOPPED`，不再继续推送
- [ ] 注册 `onTimeout` / `onError` / `onCompletion`，任一触发后置位"客户端已断开"，工作线程发送前检查该标志
- [ ] 发送抛异常视为断开，停止后续推送，不重复发送 `error`
- [ ] 单测覆盖拒绝、提交异常、超时、发送失败四条路径
- [x] 客户端断开（abort / 删除会话 / 超时）时主动取消上游生成订阅，不再继续消费 token；该轮仍落 `STOPPED` 并保留已生成正文，会话锁随即释放

## Comments

### 2026-11-03 断开即取消上游生成（联调反馈的回填，归属票 09）

**为什么归到 09 而不是新开票**：这是票 09「断连兜底」的同一段代码与同一组生命周期回调
（`onTimeout` / `onError` / `onCompletion` / 发送失败 → `RoundStream.disconnected()`）。
原实现只置位"不再推送"，`generate` 仍会把模型流消费到自然结束；本次在同一个断开入口补上
"取消上游"动作，没有新的 HTTP 契约，也没有新的失败语义，所以并入 09。

**实现**

- 新增 [AnswerCancellation](../../src/main/java/com/lender/assistant/rag/strategy/AnswerCancellation.java)：
  `isCancelled()` 轮询 + `onCancel(Runnable)` 注册（支持"先取消、后注册"的竞态，动作至多执行一次）。
- `AnswerGeneratorStrategy.generate` 增加 `AnswerCancellation` 参数（内部接口，不改 HTTP/SSE 契约）：
  - Mock 实现：每片 token 之间检查标志，取消即停止切片与回调；
  - OpenAI 兼容实现：`flux.takeUntilOther(sink.asMono())`，取消时 dispose 上游订阅并让 `blockLast()` 立刻返回
    （Flux 可能正卡在等下一个 token 的网络等待里，只靠标志位停不下来）。
- `QaServiceImpl.RoundStream` 持有取消句柄；`disconnected()` 与 `timeout()` 在置位断开、落 `STOPPED`
  的同一时刻 `cancel()`。`finished` CAS 不变，一轮仍只有一条 assistant 行。
- 工作线程 `finally` 释放会话锁不受影响：`generate` 提前返回后锁立即释放。

**单测（先红后绿）**

`QaServiceTest#clientDisconnectCancelsUpstreamGeneration`：伪实现 150 片 × 20ms（自然结束约 3s），
`FailAfterEmitter(3)` 在第 1 片后模拟 abort：

- 红（未接取消）：耗时 4832ms，150 片全部消费；
- 绿：耗时约 40ms，只消费 2 片；落库 `STOPPED`、正文以"片段0"开头、`error` 为空。

全量 `mvn test`：162 个测试全绿（清 `target/test-classes` 后跑）。

**真库实测（Mock 模型，topK=8，自然结束 2988ms）**

| 观测点 | 数值 |
|---|---|
| 请求开始 | 12:40:36.971 |
| `curl --max-time 2` 断开 | 12:40:39.051 |
| `STOPPED` 落库（`create_time`） | 12:40:39.060（断开后 9ms） |
| 自然结束对照 | 约 12:40:39.96（比落库晚约 0.91s） |
| 落库正文长度 | 708 字符（完整回答 1020 字符，约 69%，证明 token 消费提前终止） |
| 锁释放 | 断开后 0.28s 立即追问同一会话 → `200`，未出现 409 |

**未覆盖 / 遗留**

- OpenAI 实现的 `takeUntilOther` 取消路径没有单测（无真实模型/无 ChatClient 测试替身）；本次实测是 Mock 路径。
- 断开后 Spring 的 async 错误派发会让 `GlobalExceptionHandler` 尝试把 `ApiResponse` 写进已提交的
  `text/event-stream`，日志出现 `HttpMessageNotWritableException` 警告——属既有噪声，与本次取消无关，未改。

