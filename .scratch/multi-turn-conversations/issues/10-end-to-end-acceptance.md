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

### 2026-11-03 收尾修复：重复文档清理 + 入库幂等

阶段五诊断「响应字段呢？」多轮指代失败时，发现知识库里存在两份同内容的华信接口文档：本票端到端冒烟
（`java -jar` + Mock 模型）把样本以 `e2e_huaxin_api` 之名灌入了真实库，而业务原始文档是
`huaxin_corp_api_v1`。两份各 8 切片、同样占用关键词/向量通道名额，交易查询接口因此多占 2 个候选位。

#### 2a 删除依据（不是凭 docId 名字）

核对 SQL（对 `section_path + '\n' + content` 逐片取 md5）：

| doc_id | 切片数 | 总字符 | 入库时间(UTC) |
|---|---|---|---|
| `huaxin_corp_api_v1` | 8 | 1947 | 2026-10-11 07:54:52 / 更新 2026-10-29 14:50:21 |
| `e2e_huaxin_api` | 8 | 1947 | 2026-11-02 18:07:12 |

- 8 个 `chunk_index` 的 `section_path` 与 `content` md5 **逐条完全相同**（`same = t` × 8）；
- 8 条 `embedding` 的 md5 也**逐条相同**（同内容 + 确定性 hash embedding）；
- `doc_title` 与 `source_path` 也一致（均为 `华信银行_对公收付接口文档_v1.0(.docx)`）；
- 入库时间落在本票冒烟窗口（2026-11-02 18:07 UTC = 2026-11-03 02:07 +08），
  且本文档「端到端冒烟」章节明确记录了「样本 `e2e_huaxin_api`（8 chunks）已入库」。

结论：`e2e_huaxin_api` 是本票测试灌入的重复副本，内容与 `huaxin_corp_api_v1` 无差异，删除不影响业务数据。

删除前的切片正文留档（万一删错可据此恢复）：

```text
0 | 华信银行 对公收付接口文档 > 1 接入约定 | 本接口用于合作机构通过 HTTP 通道发起对公账户的代收与代付交易。生产环境网关地址为 https://fake-huaxin.example.com/gateway，联调环境为 https://fake-huaxin-uat.example.com/gateway。请求报文统一使用 UTF-8 编码的 JSON。
所有接口采用 HTTPS 双向证书认证。合作机构需向我行提供机构证书公钥，我行分配合作机构编号（merNo）与接入密钥（secretKey）。
1 | 华信银行 对公收付接口文档 > 1 接入约定 > 1.1 通用请求头 | 参数名: Content-Type | 类型: string | 必填: 是 | 说明: 固定值 application/json
参数名: X-HX-Timestamp | 类型: string | 必填: 是 | 说明: 请求时间戳，格式 yyyyMMddHHmmss，与我行服务器时间偏差不得超过 5 分钟
参数名: X-HX-Sign | 类型: string | 必填: 是 | 说明: 签名，规则见 1.2 节
参数名: X-HX-MerNo | 类型: string | 必填: 是 | 说明: 合作机构编号
2 | 华信银行 对公收付接口文档 > 1 接入约定 > 1.2 签名规则 | 将请求体 JSON 按 key 的字典序升序排列后拼接为 key1=value1&key2=value2 形式，末尾拼接 secretKey，对整个字符串做 SHA-256 摘要，十六进制大写即为 X-HX-Sign。签名失败返回错误码 SIGN_ERROR。
3 | 华信银行 对公收付接口文档 > 2 单笔代收接口 | 代收指从我行企业客户的对公账户扣划资金至合作机构归集账户。单笔限额 500 万元，日累计限额 5000 万元。接口路径 /v1/collections/single。
参数名: outBizNo | 类型: string(32) | 必填: 是 | 说明: 合作机构侧业务流水号，需保证唯一
参数名: payerAccount | 类型: string(32) | 必填: 是 | 说明: 付款方对公账户号
参数名: payerName | 类型: string(128) | 必填: 是 | 说明: 付款方户名
参数名: payeeAccount | 类型: string(32) | 必填: 是 | 说明: 收款归集账户号
参数名: amount | 类型: decimal(16,2) | 必填: 是 | 说明: 交易金额，单位元，保留两位小数
参数名: remark | 类型: string(200) | 必填: 否 | 说明: 附言
响应中的核心字段：hxSeqNo 为我行流水号，status 取值 PROCESSING/SUCCESS/FAIL。最终态以对账文件或交易查询接口为准，状
4 | 华信银行 对公收付接口文档 > 2 单笔代收接口 | (200) | 必填: 否 | 说明: 附言
响应中的核心字段：hxSeqNo 为我行流水号，status 取值 PROCESSING/SUCCESS/FAIL。最终态以对账文件或交易查询接口为准，状态为 PROCESSING 时不得直接作为失败处理。
5 | 华信银行 对公收付接口文档 > 3 单笔代付接口 | 代付指从我行归集账户付款至指定对公账户。接口路径 /v1/payments/single。请求字段与代收接口一致，另有 feeFlag 字段：手续费承担方，取值 PAYER/PAYEE，默认 PAYER。
6 | 华信银行 对公收付接口文档 > 4 交易查询接口 | 接口路径 /v1/transactions/query。支持按 outBizNo 或 hxSeqNo 查询，二者至少传一个。查询仅返回近 90 天内的交易。
响应字段: hxSeqNo | 类型: string(32) | 说明: 我行流水号
响应字段: outBizNo | 类型: string(32) | 说明: 机构侧流水号
响应字段: status | 类型: string | 说明: PROCESSING/SUCCESS/FAIL
响应字段: failCode | 类型: string | 说明: 失败码，status=FAIL 时返回
响应字段: failMsg | 类型: string(256) | 说明: 失败原因描述
响应字段: completeTime | 类型: string(14) | 说明: 完成时间 yyyyMMddHHmmss
7 | 华信银行 对公收付接口文档 > 5 错误码表 | 错误码: SIGN_ERROR | 含义: 验签失败 | 处理建议: 检查签名规则与 secretKey
错误码: DUPLICATE_OUTBIZNO | 含义: 机构流水号重复 | 处理建议: 查询原交易状态，勿重复发起
错误码: AMOUNT_LIMIT | 含义: 超过限额 | 处理建议: 单笔/日累计限额见 2 节
错误码: ACCOUNT_NOT_EXIST | 含义: 账户不存在 | 处理建议: 核对账户号与户名
```

删除结果（真实库，`DELETE FROM doc_chunk WHERE doc_id='e2e_huaxin_api'`，返回 `DELETE 8`）：

| 指标 | 删除前 | 删除后 |
|---|---|---|
| `doc_chunk` 总行数 | 25 | 17 |
| 文档数（`COUNT(DISTINCT doc_id)`，本库无独立 doc 表） | 4 | 3 |
| `huaxin_corp_api_v1` 切片 | 8 / 1947 字 | 8 / 1947 字（未动） |

#### 2b 入库幂等（内容哈希判重）

- 判重发生在**解析 → 切分之后、向量化之前**：此时切片文本已确定，能判中「同内容不同文件名」；
  命中后直接返回既有文档的 `docId`（`UploadResponse` 结构不变、不新增错误码），并跳过整轮向量化与写库。
- 哈希口径：切片「章节路径 + 换行 + 正文」的有序序列做 SHA-256，不含 `docId` / 文件名 / `doc_title`。
- 成本控制：先用 `listDocuments()` 的「切片数 + 总字符数」预筛候选，再读候选文档的切片正文比对哈希，
  避免每次上传都读全库正文。
- 已知边界：同一 `docId` 上传同内容也走判重（等价于不重建，`update_time` 不刷新）；同内容不同文件名时
  不会更新 `doc_title`（因为不重建）。如需强制重建，应先把旧文档删除再上传。
- 单测：`DocumentServiceTest` 覆盖「同内容不同 docId → 不新增、返回既有 docId、不做向量化」与
  「切片数/总字符数相同但内容不同 → 正常入库」。

真实接口验证（重启后的实例，`POST /api/admin/documents`，同一份 `华信银行_对公收付接口文档_v1.0.docx`
以新 docId `verify_duplicate_check` 上传）：

```json
{"code":200,"msg":"ok","data":{"docId":"huaxin_corp_api_v1","blocks":19,"chunkCount":8,
 "avgChunkChars":243.375,"millis":{"parse":1617,"split":2,"embed":0,"store":0},
 "embeddingProvider":"HashEmbeddingStrategy"}}
```

返回的是既有文档 `huaxin_corp_api_v1`，`millis.embed=0 / store=0`；删除前后 `doc_chunk` 行数仍为 17、
文档数仍为 3，未新增任何切片。
