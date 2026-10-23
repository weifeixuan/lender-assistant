# 代码规范 · 资方接入智能助手

> **给 AI Agent**：写代码前读完本文并逐条遵守。只约束**新增与改动**，存量不强制改造。
> **冲突优先级**：本文件 > 个人习惯 > 通用规范；拿不准时看同包已有代码的写法。
> **为何这么定**：取舍理由见 `docs/adr/`，本文只留可执行的规则。

## 0. 包结构

### 0.1 按领域分包 + 领域内按层

```text
com.lender.assistant
├── rag/              # RAG 领域
│   ├── controller/   #   QaController / EvalController / DocumentAdminController
│   ├── service/      #   QaService / EvalService / SearchService / DocumentService（+ impl/）
│   ├── strategy/     #   可替换策略（+ impl/；factory/ 仅按文件格式分发用）
│   ├── mapper/       #   DocChunkMapper（+ resources/mapper/DocChunkMapper.xml）
│   ├── vo/request/   #   *Request（QaRequest / SearchRequest）
│   ├── vo/response/  #   *Response（SearchItemResponse / UploadResponse）
│   └── model/        #   领域内跨层 record（Chunk / DocContent / VectorHit / ParsedDocument）
├── extract/          # controller / service(+impl) / mapper / vo(request,response) / model / strategy(+impl)
├── draft/            # controller / service(+impl) / vo/response / strategy(+impl)
├── tools/            # Function Calling 工具（无 HTTP 入口）
├── resilience/       # ResilientLlmExecutor / LlmCallLogger（+ mapper/ + model/）
├── ops/              # controller/HealthController（GET /health）
├── common/           # annotation / constant / dto / exception / util / web
├── config/           # @Configuration / @Bean
└── LenderAssistantApplication.java
```

### 0.2 结构规则

- 领域内分层 `controller` / `service` / `strategy` / `mapper` / `vo` / `model`；**顶层禁止建** `controller/` `service/` `mapper/` 技术包。
- **对外契约一律进 `vo/`**：`vo/request/*Request`、`vo/response/*Response`；`model/` 只放领域内跨层 / 映射 record，两者不混用。
- 无 HTTP 入口的包（`tools` / `resilience` / `common`）不建 `controller/`；要访问 DB 才建 `mapper/`。
- 新类定位顺序：领域 → 层 → 都定不了才进 `common/`，并在 commit message 说明理由。
- 禁止平行单类包、近义包（`extract` vs `extraction` 出过事故）、空包、死代码。
- 可替换策略统一进 `strategy/`（接口 + `impl/`）；只有运行期按参数选实现才加 `factory/`。

## 1. 分层职责与依赖

| 层 | 允许 | 禁止 |
|---|---|---|
| `controller/` | 参数校验、调 service、组装响应、异常→状态码 | 业务逻辑、SQL、直接操作数据源 |
| `service/` | 业务编排、事务边界、调 mapper / client / tools | 拼 SQL、拼响应细节 |
| `mapper/` | 全部 SQL、行↔对象映射 | 业务判断、`@Transactional` |
| `vo/` | 对外请求 / 响应 `record` | 业务逻辑、可变状态 |
| `model/` | 领域内跨层 `record` | 业务逻辑、可变状态 |
| `common/util/` | 无状态静态方法 | 持有 Spring Bean、实例状态 |

- 依赖方向 `controller → service → mapper`，禁止反向与跨层；跨领域只允许 service → 另一个领域的 service。
- 可替换实现必须接口 + `@ConditionalOnProperty` 切换（`EmbeddingStrategy` / `AnswerGeneratorStrategy` / `ExtractGeneratorStrategy` / `DraftGeneratorStrategy` / `DocumentParserStrategy` / `ChunkSplitterStrategy`）；实现选择交给 Spring 装配，**禁止业务代码 `if-else` 判 mock / deepseek**。
- 编排型 Service 统一 `XxxService` + `service/impl/XxxServiceImpl`；策略接口以 `Strategy` 结尾，实现「策略名 + 接口名」放 `strategy/impl/`。
- 跨层传值一律 `record`，禁止可变 DTO + setter。

## 2. 命名

1. 类 `UpperCamelCase`；方法 / 变量 `lowerCamelCase`；常量全大写下划线。
2. 抽象类 `Abstract` / `Base` 前缀；异常 `Exception` 结尾；枚举成员全大写。
3. Service 接口不加 `I` 前缀，实现类以 `Impl` 结尾；boolean 不加 `is` 前缀（序列化会丢）。
4. 禁止不表意命名：`data` / `result2` / `temp` / `flag` / 单字母（循环 `i/j/k` 除外）。
5. 包名全小写、不用复数（`rag.strategy`）；测试类 = 被测类 + `Test`，方法表达行为 + `@DisplayName` 中文场景句。
6. 4 空格缩进；单行 ≤ 120；单方法 ≤ 40 行；单类 ≤ 500 行；方法参数 ≤ 4 个（超出收 `record`）。

## 3. 方法与代码块

1. 一个方法只做一件事；变长先想能否拆分（私有方法或 `util`）。
2. Rule of Three：第 3 次出现前必须抽取；第 2 次出现时两处注释互标「与 XX 重复」。
3. 魔法值零容忍：字面量出现 ≥2 次、或语义不自明（URL 前缀、阈值、场景名）必须提为常量，归 `common/constant/` 或所在类顶部。
   - **跨边界标签**（要落库 / 出现在 JSON 里的标识，如 `LlmScene`、`SearchMode`）：用 `code` / `desc` 双字段枚举，调用点直接传枚举，只在落库 / 出参处取 `code()`。
   - **纯数值常量**（超时、阈值、容量）：用 `*Constants`，禁实例化。
   - 边界解析（JSON → 枚举）只留一个入口（如 `SearchMode.fromCode`），未识别值直接拒绝，不静默回落。
4. 禁止嵌套三元等「聪明代码」；可读性 > 简短。
5. 公开类 / 方法必须有 Javadoc 说明用途与取舍；行内注释讲「为什么」，不复述代码。
6. 同一规则 / 算法只允许一处实现。

## 4. 增量代码生成

1. 改类前先读目标类 + 同包相邻类；与已有类相似度 > 50% 时扩展它，不新建。
2. 新建前 `Grep` 查同名 / 同职责 / 近义包；新包必须能在 §0.1 树里指认位置。
3. 先复用后新建：已有方法不满足需求时优先重载 / 扩参，禁止复制改名。
4. 禁止死代码与半成品：不留注释掉的代码、不建空包、不留「以后可能用」；必须编译 + 测试通过，`TODO` 只用于明确的待人工补全点。
5. 改 A 不顺手改 B：发现相邻问题先提出、确认后再修。
6. 示例数据全脱敏虚构，时间取真实时间；资方名 / URL / 字段值必须与 `docs/gen_sample_docs.py` 口径一致。

## 5. 异常、校验与日志

1. 禁止吞异常：要么降级兜底，要么包装语义后上抛；业务失败抛 `BizException(ErrorCode)`，统一 `@RestControllerAdvice`，禁止裸 `RuntimeException` 做流程控制（见 [ADR-0002](../adr/0002-business-exception-package.md)）。
2. 对外接口入参必须校验：非法值拒绝并给默认兜底，禁止直接 500。
3. 降级必须留痕：模型调用失败经 `LlmCallLogger` 记 `FAIL` + `error_msg`（旁路，留痕失败不挡主链路）。
4. 日志用 SLF4J 占位符，异常 `log.error("上下文", e)` 带堆栈；禁止 `e.printStackTrace()` / `System.out`；级别 ERROR=需人处理 / WARN=自动恢复但有异常 / INFO=关键节点 / DEBUG=细节；禁止打印身份证 / 卡号 / 密钥，关键上下文带 `docId` / `chunkId`。
5. 对外响应统一 `ApiResponse{code, msg, data}`（`ResponseWrapAspect` 切面包装，失败 `data` 为 null），不暴露堆栈 / 内部类名 / SQL；SSE 与探针接口用 `@RawResponse` 排除包装（见 [ADR-0001](../adr/0001-unified-response-envelope.md)、[ADR-0007](../adr/0007-wrap-aspect-and-json-logging.md)）。
6. MVC 客户端错误按 HTTP 语义归位，不得兜成 500：路由不存在 → 404；报文不可解析 / 参数类型不匹配 / 必填缺失 → 400，且不回声框架技术信息（见 [ADR-0005](../adr/0005-streaming-and-error-semantics.md)）。

## 6. 数据访问与事务（MyBatis）

> 数据访问统一 MyBatis，行↔对象映射与事务边界见 [ADR-0004](../adr/0004-data-access-mybatis-conventions.md)。

1. SQL 只在 `mapper/`；controller / service 不得出现 SQL 字符串。
2. 接口 `XxxMapper` + `resources/mapper/XxxMapper.xml`，开启 `map-underscore-to-camel-case=true`。
3. 占位一律 `#{}`，**严禁 `${}`**（Tool 参数来自模型，属外部输入）。
4. 禁止 `SELECT *`；动态条件用 `<where>` + `<if>`，重复片段用 `<sql>` + `<include>`；分页手写 `LIMIT #{limit} OFFSET #{offset}` 并另查 `COUNT(*)`，不引分页插件。
5. 结果映射用显式 `<resultMap>`；record 配 `<constructor>`，**数值组件必须用包装类型**（`Integer` / `Long` / `Double`），XML `javaType` 写显式包装类名，可空列用 `COALESCE(col, 0)` 兜底。禁止返回 `Map<String,Object>`。
6. pgvector 语法只允许在 XML：向量以字符串传入并 `CAST(#{vec} AS vector)`，距离 `ORDER BY embedding <=> CAST(#{vec} AS vector)`，关键词 `SIMILARITY(content, #{kw})`。
7. 幂等写入用 upsert（`ON CONFLICT ... DO UPDATE`）；先删后插必须在注释说明为什么不用 upsert。
8. 新表必须有 `COMMENT ON`；**schema 变更同步回写 `docs/schema.sql`**（唯一建表事实源）——手动 psql 不回写 = 新环境必挂。
9. Mapper 只取数、不加 `@Transactional`，业务判定留 Service；禁止循环内单条查 DB，批量写用 `<foreach>` + `ExecutorType.BATCH`。
10. 事务边界在 Service：`@Transactional(rollbackFor = Exception.class)`；非原子的「删+插」与多表写必须加事务。
11. 事务内禁止长耗时操作（模型调用、文件 IO）与循环内远程 / DB 调用。

## 7. 并发与异步

1. 禁止 `Executors` 建池（无界队列 OOM）→ 统一 `common/util/ThreadPoolFactory.create`（命名 daemon 线程 + 有界队列 + AbortPolicy）；`@PreDestroy` 关停留持有该池的 Bean。
2. SSE 必须立即 `return emitter`，检索 / 生成 / `send` 放独立线程，否则被缓冲成假流式（见 [ADR-0005](../adr/0005-streaming-and-error-semantics.md)）。
3. 韧性用编程式 `decorateSupplier`（`ResilientLlmExecutor`），不用注解 AOP；AOP 仅用于 controller 日志切面。
4. 共享可变对象（`SimpleDateFormat` / `ArrayList` / `HashMap`）不作成员变量，时间用 `DateTimeFormatter`；`foreach` 中不增删元素；高并发计数用 `AtomicInteger` / `LongAdder`。
5. `Collectors.toMap` 必须显式 merge function（能证明 key 唯一须注释说明）。

## 8. 常量、配置与密钥

- 可配置项走 `@Value` / 配置类，集中 `application.yml`；新增配置必须给默认值且可开关（如 `rag.search.max-distance` 默认关闭）。
- 密钥 / 口令走环境变量，禁止入库；占位假值须显式标注。
- 新增依赖能不加就不加。

## 9. 单元测试

1. AIR：自动、独立、可重复（不依赖外部环境）。
2. 不连真库：mock Mapper，但协作对象用真实实例优于过度 mock（保留真实 `LlmCallLogger` 等，只 mock 数据访问）。理由见 [ADR-0006](../adr/0006-testing-and-schema-source-of-truth.md)。
3. 一个测试只验一个行为，三段式 given / when / then，断言带消息且**值必须手算核对**。
4. 优先级：领域核心逻辑（RRF、抽取规则、熔断状态机）> 编排 > controller。
5. bug 修复先补能复现的 RED 测试再修绿。
6. 新增 / 修改公共方法必须补或改单测；**改签名 / 改同步异步契约必须同步 `src/test`，改完 `mvn test` 全绿**。
7. Mapper / DB 链路不写 `@SpringBootTest` 真库冒烟测试，由起服务后手工验证覆盖；改 Mapper / `<resultMap>` / starter 依赖必须真机走一遍（见 [ADR-0006](../adr/0006-testing-and-schema-source-of-truth.md)）。

## 10. 提交纪律与自检

1. commit 前 `mvn test` 全绿；测试数量下降必须说明原因。
2. message 沿用 `阶段X(partY): 主题` + 要点列表；一次改动聚焦一个目标。
3. 禁止 `git add -A` 夹带无关文件；`app.log` / `target/` 不入库。
4. 架构级取舍先写 `docs/adr/`。

提交前自检：

- [ ] 已搜过并复用现有公共方法 / SQL 片段
- [ ] 新类已归位（领域 + 层），未建平行 / 近义包
- [ ] 入参有校验与兜底；异常走全局处理，无吞异常
- [ ] 非原子写有事务；线程池走工厂且可关闭；SSE 立即 return

## 11. 红线

❌ `System.out` / `e.printStackTrace()`　❌ 吞异常 / 空 catch　❌ 拼接 SQL / `${}` / `SELECT *`
❌ `Map<String,Object>` 作跨层 / 对外契约（唯一例外：`@RawResponse` 探针 `HealthController.health()`，compose 验收脚本按其结构解析）　❌ 魔法值 / 硬编码密钥　❌ `Executors` 建池
❌ 无 HTTP 入口的包建 `controller/`　❌ 顶层建技术包　❌ 空包 / 死代码
❌ 业务代码 `if-else` 判 mock / deepseek　❌ 复制粘贴改名　❌ 改签名不同步调用方与测试
