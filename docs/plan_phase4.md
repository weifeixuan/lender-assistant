# 阶段 4 实施计划：结构化抽取 + Function Calling + 韧性

> 方法论：superpowers（头脑风暴 → 计划 → TDD 执行 → 评审 → 收尾）  
> 规格来源：手册"阶段 4"章节 + 2026-10-12 四项设计决策（用户已确认）  
> 执行模式：executing-plans 人工检查点（遵守红线"AI 代码必须看懂"）

## 一、规格确认记录

| 决策点   | 结论                                               | 理由                                 |
| ----- | ------------------------------------------------ | ---------------------------------- |
| 实现策略  | Mock 先行 + DeepSeek 双实现，`rag.extract.provider` 切换 | 沿用阶段 2 模式；单测脱离网络/key 可跑；key 到位一键复测 |
| 内部字段集 | 从现有 3 份虚构文档反推 12 个字段，写死为内部标准 schema              | 评估准确率有明确对照物；字段即真实接入适配层需要的信息        |
| FC 工具 | ① 查已入库文件列表 ② 查某文档 chunk 数                        | 手册原文示例 + 真实查库有数据；Mock 用规则模拟"模型决策"  |
| 故障模拟  | `rag.resilience.chaos=true` 配置开关注入故障             | 可重复、可进自动化测试；验收演示用真实停容器补充截图         |
| Git   | 不建分支/worktree（用户确认暂不接入 git）                      | 阶段 4 直接在主干目录开发，收尾用 git commit 记录   |

## 二、内部标准字段集（12 个，从华信/瑞金文档反推）

| #  | 字段ID           | 名称             | 文档中的出处（抽取素材验证点）                                                  |
| -- | -------------- | -------------- | ---------------------------------------------------------------- |
| 1  | lender_name    | 资方名称           | 文档标题"华信银行/瑞金租赁"                                                  |
| 2  | gateway_url    | 生产网关地址         | "生产环境网关地址为 <https://fake-huaxin…"、"网关地址> <https://fake-ruijin…>" |
| 3  | uat_url        | 联调环境地址         | "联调环境为 <https://fake-huaxin-uat…>"                               |
| 4  | auth_method    | 鉴权方式           | "HTTPS 双向证书认证"、"RSA2 私钥签名"                                       |
| 5  | credentials    | 准入凭证字段         | "merNo 与 secretKey"、"partner_id 与 partner_key"                   |
| 6  | sign_rule      | 签名规则           | "字典序升序…SHA-256 摘要，十六进制大写"、"签名放置于请求体字段 sign"                      |
| 7  | payment_api    | 付款/放款指令接口      | "/v1/payments/single"、"/openapi/loan/order/create"               |
| 8  | collection_api | 收款/代收接口        | "/v1/collections/single"（瑞金无 → 期望"资料中未提及"）                       |
| 9  | query_api      | 交易查询接口         | "/v1/transactions/query"                                         |
| 10 | status_enum    | 交易状态枚举         | "PROCESSING/SUCCESS/FAIL"、"SUCCESS / FAILED / PROCESSING"        |
| 11 | file_delivery  | 对账文件获取方式与时点    | "每日 23:30 生成，通过 SFTP 推送"、"T+1 日上午 10 点前开放对账单下载接口"                |
| 12 | file_format    | 对账文件格式（编码/分隔符） | "编码 GBK，分隔符竖线"、"CSV，UTF-8 编码，逗号分隔"                               |

设计说明：**为什么不含逾期文件列定义**——那是"字段级明细"，属于映射表下钻数据而非 12 个接入级字段；阶段 4 的准确率对照物保持精简，避免瑞金文档里"不存在的字段"稀释数字。错误码表同理不入清单。

## 三、架构设计（新增部分）

```
ExtractController  POST /api/extract/{docId}        ─┐
FieldMappingStore   field_mapping 表(确认/修正落库)  │ com.lender.assistant.extract
InternalFieldCatalog 12字段常量                     │
ExtractGenerator    接口: extract(docText) → 映射表  │
  ├ MockExtractGenerator     规则+正则(默认)         │
  └ DeepSeekExtractGenerator JSON Schema 抽取        ─┘
LenderQueryTools    @Tool ×2: 文件列表/chunk数        ─ com.lender.assistant.tools
  └ MockAnswerGenerator 扩展: 工具意图识别(模拟模型决策)
ResilientLlmExecutor  纯 resilience4j 编程式(CB+Retry) ─ com.lender.assistant.resilience
LlmCallLogger         llm_call_log 留痕(耗时/token/成本)
  └ QaService/ExtractService 统一经它调用生成器；熔断→降级消息(检索结果照常返回)
```

**关键设计决策（为什么这么写）**：

1. **Resilience4j 用纯库编程式而非 spring-boot3 starter**：注解式靠 AOP 魔法，单测要起容器；编程式 `CircuitBreaker.decorateSupplier` 一个纯 JUnit 测试就能驱动红绿循环，也方便你逐行看懂熔断状态机。
2. **留痕器做装饰器而非散在各处**：`LlmCallLogger.record(scene, supplier)` 包住每次生成器调用，QA/抽取两条链路零重复代码；成本 = token × 单价从配置读（mock 记 0）。
3. **Mock 的"模型决策"是规则模拟**：问题命中工具意图关键词（"已入库/哪些文件/chunk数"）→ 真实调 LenderQueryTools → 答案带 `[工具调用]` 标记；DeepSeek 版走 Spring AI `.tools()` 真决策。Mock 决策逻辑独立成 `ToolIntentRouter`，可单测。
4. **降级不吞检索**：熔断只影响生成段，sources 照常 SSE 下发，错误事件带"已转人工"文案——手册验收"系统降级不崩"。

## 四、数据库变更（docs/schema_phase4.sql）

```sql
CREATE TABLE IF NOT EXISTS field_mapping (
  id BIGSERIAL PRIMARY KEY,
  doc_id TEXT NOT NULL,
  internal_field_id TEXT NOT NULL,
  lender_field_name TEXT,
  lender_value TEXT,
  source_quote TEXT,
  confidence NUMERIC(4,3),
  status TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING/CONFIRMED/CORRECTED
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(doc_id, internal_field_id)
);
CREATE TABLE IF NOT EXISTS llm_call_log (
  id BIGSERIAL PRIMARY KEY,
  scene TEXT NOT NULL,          -- qa / extract
  provider TEXT NOT NULL,       -- mock / deepseek
  duration_ms INT,
  prompt_tokens INT, completion_tokens INT,
  cost_cny NUMERIC(10,6) DEFAULT 0,
  status TEXT NOT NULL,         -- OK / TIMEOUT / FAIL / CIRCUIT_OPEN
  error_msg TEXT,
  created_at TIMESTAMPTZ DEFAULT now()
);
```

## 五、任务清单（TDD 顺序，每步先红后绿）

| #  | 任务                                                           | 文件                                                                   | 验证                                                 |
| -- | ------------------------------------------------------------ | -------------------------------------------------------------------- | -------------------------------------------------- |
| 0  | **测试基线**：给现有 SearchService RRF 融合、QaService Prompt 拼接补单测     | `src/test/java/.../SearchServiceTest.java`、`QaServiceTest.java`      | `mvn test` 全绿=基线                                   |
| 1  | InternalFieldCatalog：12 字段常量 + 校验测试                          | `extract/InternalFieldCatalog.java`                                  | CatalogTest（id 唯一、12 个、无 null）                     |
| 2  | FieldMapping record                                          | `extract/FieldMapping.java`                                          | 随 3 一起验证                                           |
| 3  | MockExtractGenerator：按第二节出处表写正则规则                            | `extract/MockExtractGenerator.java`                                  | **RED**：瑞金样例文本 fixture → 期望映射断言 → **GREEN** 实现规则   |
| 4  | ExtractService：拼 chunks→调生成器→留痕；chaos 开关                     | `extract/ExtractService.java`                                        | ExtractServiceTest（generator 被调、chaos 抛超时→留痕 FAIL） |
| 5  | ExtractController + FieldMappingStore + 确认接口                 | `controller/ExtractController.java`、`extract/FieldMappingStore.java` | 启动应用 curl 全链路（PG 集成验证）                             |
| 6  | LenderQueryTools 两方法 + ToolIntentRouter                      | `tools/LenderQueryTools.java`                                        | ToolIntentRouterTest（意图命中/不命中）                     |
| 7  | MockAnswerGenerator 接工具；DeepSeekAnswerGenerator 加 `.tools()` | `rag/qa/` 两文件                                                        | MockToolAnswerTest（触发后答案含工具结果）                     |
| 8  | ResilientLlmExecutor：CB+Retry+chaos                          | `resilience/ResilientLlmExecutor.java`                               | **RED**：连续失败→CircuitBreaker OPEN→拒绝→**GREEN**      |
| 9  | LlmCallLogger + 接入 QA/Extract 链路 + 降级路径                      | `resilience/LlmCallLogger.java`、`rag/qa/QaService.java`              | LoggerTest；QA 熔断时 SSE error 事件带降级文案                |
| 10 | UI：index.html 抽取区块 + 留痕查询入口                                  | `static/index.html`                                                  | 浏览器操作                                              |
| 11 | 抽取准确率评估：瑞金文档 golden 对照                                       | `evalset` + 测试或脚本                                                    | 准确率数字进验收报告                                         |
| 12 | 验收演练：chaos 演练 + 真实停容器演练 + 阶段4文档                              | `docs/阶段4_*.md`                                                      | 手册验收标准逐条打勾                                         |

## 六、验收标准（来自手册，逐条对照）

1. 虚构新资方文档进 → 映射表出，**准确率有数字**（瑞金文档 + golden 对照，报告进 docs/eval/）
2. 模拟 API 挂掉（chaos 开关 + 停容器双重演练），系统降级不崩，QA 仍能返回检索来源
3. 每次模型调用留痕可查（llm_call_log 表 + 页面入口）
4. 人工确认/修正映射表入口可用（界面确认 → CONFIRMED 落库）

## 七、红线检查点

- 全程虚构资方（fake-*.example.com），无真实机构信息
- 时间真实：文档/代码中日期用当前真实时间
- 每个 GREEN 后我会停下讲设计决策，你确认看懂再进下一任务
