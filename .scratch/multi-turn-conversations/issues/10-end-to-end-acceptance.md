# 10: 旧操作台下线与端到端验收

**What to build:** 多轮接口全部就位后，删除已被取代的单轮静态操作台，并跑通一条贴近真实用户的端到端链路：首问建会话 → 列表可见 → 追问 → 历史完整 → 刷新后仍在 → 删除后消失。确认没有遗留的旧单轮语义与两套互相矛盾的说法。

**涉及层次:** 静态资源清理、文档一致性检查、端到端冒烟（HTTP + SSE + 数据库）。

**Blocked by:** 01, 02, 03, 04, 05, 06, 07, 08, 09

**Status:** ready-for-agent

**Estimate:** 1–1.5 小时

- [x] 旧单轮静态操作台已从仓库删除，仓库中不再有指向它的说明或链接
- [x] 首问：无 `conversationId` 请求得到 `meta` 首事件；列表第一条为新建会话且高亮所需字段齐全
- [x] 追问：携带 `conversationId` 成功追加一轮，模型上下文包含上一轮问答
- [x] 历史：扁平消息按 `id ASC` 返回，两条消息共享 `roundId`，引用来源可展开
- [x] 隔离：换一个 `X-User-Id` 看不到上一个用户的会话，直接访问其 id 得到 404
- [x] 删除：软删后列表与历史均不可见，消息行仍在库中
- [x] 异常：并发第二次提问得到 409；线程池拒绝得到 500 且留下 `FAILED` 记录
- [x] `docs/schema.sql` 与需求文档、spec 中的字段名、错误码、事件顺序逐字一致，无第二套说法
- [x] `mvn test` 全绿，端到端冒烟结果记录在 ticket 的 Comments 中

## Comments

### 2026-11-03 端到端冒烟（真实 PG + Mock 模型）

环境：`lender-pg`（pgvector/pg16）Up，schema 已由 `docs/schema.sql` 应用；应用 `java -jar` 起在 8080；样本
`e2e_huaxin_api`（8 chunks）已入库。模型走 Mock（`RAG_CHAT_PROVIDER=mock`、`RAG_EMBEDDING_PROVIDER=hash`）。

1. **首问** `POST /api/qa`（body `{"conversationId":null,...}`）→ SSE 首事件 `meta`，回传
   `conversationId=9b44d6f3-… / roundId=696778c0-… / userMessageId=… / assistantMessageId=…`；
   随后 `sources`（4 条）→ `delta*` → `done{provider:"Mock",elapsedMillis:1970,sourceCount:4}`。
   DB 中该轮恰好两条消息（USER/ASSISTANT 同 `round_id`，均 `DONE`）；`GET /api/conversations` 首条即新会话，
   `title` 取首问去空白前 20 code point，`createTime/updateTime` 齐全。
2. **追问** 同 body 带 `conversationId` → 新 `roundId=fb9f9401-…`，同一会话；历史装配与检索 query 走
   `ConversationHistoryAssembler`（单测 `ConversationHistoryAssemblerTest` 覆盖内容与上限）。
3. **历史** `GET /api/conversations/{id}/messages` → 4 条按 `id ASC` 扁平返回，两个 `roundId` 各占两条；
   助手消息 `sources` 为完整 JSON 数组（可展开）。
4. **隔离** `X-User-Id: e2e-user-b` → 列表 `data:[]`；访问他人会话历史 → `404`。
5. **删除** `DELETE` → `200`；随后列表为空、历史 `404`；`conversation.status='DELETED'`，
   `message` 行数不变（软删不级联）。
6. **并发 409** 同一会话两路并发提问：一路 `200`（SSE 正常完成），另一路 `409`
   `{"code":409,"msg":"会话正在生成回答，请等待本轮结束后再提问"}`。
7. **线程池拒绝 500** 用 120 路并发首问打满 `streamExecutor`（core=max=8、queue=100）：
   `108 × 200`、`12 × 500`；DB 中恰好 12 条 `FAILED` 助手消息，`error_msg` 为
   `流式任务提交失败：Task … rejected from java.util.concurrent.ThreadPoolExecutor[…pool size = 8, queued tasks = 100…]`，
   与对应 USER 消息共享 `round_id`。并发打满过程中另有 4 条 `STOPPED`（压测脚本退出关闭连接，属断连兜底路径）。

### 冒烟中修复的真实运行缺陷

`MessageMapper.insert` 的 XML 写 `#{message.xxx}`，但方法参数缺 `@Param("message")`：单参数 record 在运行时
不会被包成 `ParamMap`，导致首问落库抛
`ReflectionException: There is no getter for property named 'message' in 'class …MessageInsert'`（HTTP 500）。
已在 [MessageMapper.java](../../src/main/java/com/lender/assistant/conversation/mapper/MessageMapper.java) 补注解，
并在 `MapperXmlContractTest` 新增两条「按 MapperMethod 包装规则 + DefaultParameterHandler 取值」的回归测试
（不连库）。原单测只验证 XML 可解析，未覆盖参数绑定，这是票 01 留下的测试缝隙。

### 文档一致性

- `docs/requirements/multi-turn-backend.md` 5.2 原写「新增 MessageRole/MessageStatus/ConversationStatus 枚举类」，
  实际实现用字符串字面量（与 `docs/schema.sql` 列注释一致），已改为实际口径；`CONVERSATION_BUSY` 的描述文案
  同步为代码里的用户可见文案。
- `docs/阶段1/2/6_*.md`、`docs/adr/0001-*.md` 中的 `static/index.html` 提法是对当时提交的历史记录（时间线事实），
  不是指向现存文件的链接，保留不改。票 11 与本文均不涉及这些历史文档的改写。
- 保留的验证缺口：契约测试只证明 SQL 文本含约束，不证明真实库拒绝重复写入；该缺口由票 11（Testcontainers）承接。
