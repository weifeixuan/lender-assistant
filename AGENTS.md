# AGENTS.md · 资方接入智能助手 代码规范

> **给所有 AI Agent 的指令**：在本仓库写任何代码之前必须完整读完本文，并逐条遵守。
> **适用范围**：约束**新增与改动**的代码；存量问题不强制改造，唯一例外是数据访问层（见 §11）。
> **冲突优先级**：本文件 > 个人习惯 > 通用规范。**无法确定时，先看同包内已有代码的写法并保持一致。**
> **依据**：阿里巴巴《Java 开发手册》（嵩山版）+ 腾讯 / 字节后端工程规范，按本仓库实际技术栈裁剪。
> **口径说明**：数据访问为**统一 MyBatis**（2026-10-17 决定，取代早期 JdbcTemplate 直写方案）。
> **定稿**：2026-10-18。

---

## 0. 项目基本盘与包结构（不可违背）

### 0.1 不可违背的基本盘

- **技术栈**：JDK 21 · Spring Boot 3.x · Spring AI · PostgreSQL 16 + pgvector + pg_trgm · **MyBatis（统一）** · Resilience4j 纯库 · JUnit 5 + Mockito。**版本以 `pom.xml` 为唯一事实源，本文件不复制版本号。**
- **不引**：JPA / Hibernate / MyBatis-Plus、Lombok（用 `record` / 构造器注入）、WebFlux（Servlet 栈 + `SseEmitter`）、Tika（当前无 PDF 场景）。
- **红线**：全脱敏虚构资方数据；时间取真实时间；AI 写的代码必须人类可读懂——**注释讲「为什么」，不抄「是什么」**。
- **验证闭环**：任何改动完成前 `mvn test` 必须全绿；改了行为必须同步改测试；新增功能必须自带测试。

### 0.2 目标包结构（方案B：按领域分包 + 领域内按层）

```text
com.lender.assistant
├── rag/                       # RAG 领域
│   ├── controller/            #   QaController EvalController DocumentAdminController
│   ├── service/               #   QaService(+impl/) EvalService(+impl/)；策略组件平铺：AnswerGenerator / Mock·DeepSeekAnswerGenerator / EmbeddingClient
│   ├── mapper/                #   DocChunkMapper（原 rag/store/DocumentStore，待迁 §11）
│   ├── parser/ splitter/ embedder/   # 领域内组件，保留自有子包（不硬塞进三层）
│   └── eval/                  #   评估逻辑 + evalset.json
├── extract/                   # 结构化抽取领域
│   ├── controller/ service/ mapper/ model/
├── draft/                     # 代码草稿生成领域
│   ├── controller/ service/
├── tools/                     # Function Calling 工具（LenderQueryTools 等）——无 HTTP 入口，不建 controller/
├── resilience/                # 韧性（ResilientLlmExecutor 等）——无 HTTP 入口
│   └── mapper/                #   LlmCallLogMapper（原 resilience/LlmCallLogger，待迁 §11）
├── common/                    # 跨领域内容（领域包装不下时才进这里）
│   ├── constant/              #   *Constants（禁实例化）
│   ├── util/                  #   *Utils（静态方法 + private 构造器）
│   └── dto/                   #   跨模块 DTO
├── config/                    #   @Configuration / @Bean 装配，集中于此
├── exception/                 #   全局异常处理 + 业务异常
└── LenderAssistantApplication.java
```

### 0.3 结构规则

- **按领域分包**（`rag` / `extract` / `draft`），领域内按 **`controller` / `service` / `mapper` / `model`** 分层；**顶层禁止出现 `controller/` `service/` `mapper/` 技术包**（存量迁移见 §11，迁移完成后删除顶层空包且禁止重建）。
- **无 HTTP 入口的横向包（`tools` / `resilience` / `common`）不建 `controller/`**；需要访问 DB 时才建 `mapper/`。
- 新类先定位「哪个领域 → 哪一层」；两级都定不了再考虑 `common/`，并在 commit message 说明理由。
- **禁止**为一次性需求创建只放一个类的平行包；**禁止**包名近义重复（`extract` vs `extraction` 已出过事故）；**禁止**空包与死代码。
- 领域内组件（`parser` / `splitter` / `embedder`）保持自有子包，不硬塞进三层。

## 1. 分层职责与依赖方向

| 层 | 允许 | 禁止 |
|---|---|---|
| 领域内 `controller/` | HTTP 参数校验、调领域 service、组装 DTO 响应、异常→HTTP 状态码 | 业务逻辑、SQL、直接操作数据源 |
| 领域内 `service/` | 业务编排、事务边界、调用 mapper / client / tools | 拼 SQL、拼 HTTP 响应细节 |
| 领域内 `mapper/` | 全部 SQL 写在这里、行↔对象映射 | 业务判断、`@Transactional` |
| 领域内 `model/` | 跨层 `record` 数据载体 | 业务逻辑、可变状态 |
| `common/util/` | 无状态静态方法 | 持有 Spring Bean、实例状态 |

- **依赖方向**：`controller → service → mapper`；**禁止反向依赖与跨层**。跨领域只允许 service → 另一个领域的 service。
- **可替换实现必须有接口**：凡存在「Mock / 真实」或「多策略」可能的组件，一律**接口 + `@ConditionalOnProperty` 切换**（项目既有惯例：`EmbeddingClient`、`AnswerGenerator`、`ExtractGenerator`、`DraftGenerator`）。新增同类组件照此办理，**不允许写死单一实现**。
- **编排型 Service 统一接口 + Impl**：`XxxService`（接口）+ `service/impl/XxxServiceImpl`；策略实现类命名「策略名 + 接口名」（如 `MockExtractGenerator` / `DeepSeekExtractGenerator`）。
- **多实现的选择逻辑禁止散落**：provider 切换靠 Spring 装配，**禁止在业务代码里 `if-else` 判断「当前是 mock 还是 deepseek」**。
- **跨层传值一律 `record`**（项目惯例：`FieldMapping`、`DocContent`、`KeywordHit`），禁止可变 DTO + setter 满天飞。

## 2. 命名规范

1. 类名 `UpperCamelCase`；方法名、变量名 `lowerCamelCase`；常量全大写下划线分隔。
2. 抽象类 `Abstract` / `Base` 开头；异常类 `Exception` 结尾；枚举成员全大写。
3. **Service 接口不写 `I` 前缀**；实现类以「策略名 + 接口名」结尾。
4. boolean 变量不加 `is` 前缀（避免序列化丢前缀）。
5. **杜绝不表意的命名**：`data`、`result2`、`temp`、`flag`、单字母（循环变量 `i/j/k` 除外）。
6. 包名全小写单词、**不用复数**（`rag.splitter`，不是 `rag.splitters`）。
7. 测试类 = 被测类名 + `Test`；测试方法名表达行为 + `@DisplayName` 中文场景句。
8. **格式**：4 空格缩进；单行 ≤ 120 字符；**单方法 ≤ 40 行**；单类 ≤ 500 行；**方法参数 ≤ 4 个**（超出收敛为 `record`）。

## 3. 方法与代码块

1. **单一职责**：一个方法只做一件事；超过 **40 行**必须审视能否拆分（抽同包私有方法或 `util`）。
2. **Rule of Three**：同一段逻辑出现第 3 次之前必须抽取；**出现第 2 次时必须在两处注释互相标注「与 XX 重复，第三次出现时抽取」**。
3. **魔法值零容忍**：字面量出现 ≥2 次、或语义不自明的（URL 前缀、阈值、场景名 `"extract"`/`"qa"`），必须提为常量并归 `common/constant/` 或所在类顶部。
4. 禁止一行多逻辑的「聪明代码」（嵌套三元、逗号表达式）。**可读性 > 简短**。
5. **注释**：公开类 / 方法必须有 Javadoc 说明**用途与设计取舍**（为什么这么做）；复杂逻辑行内注释解释「为什么」，不复述代码。
6. 同一规则 / 算法**只允许一处实现**（单一事实源）。

## 4. 增量代码生成专项（历史重灾区，逐条强制）

1. **写前必读**：改任何类之前，先完整读目标类 + 同包相邻类。新需求与已有类**相似度高于 50% 时，扩展已有类**而非新建。
2. **新建文件前先全局搜索**：`Grep` 确认没有同名 / 同职责类（**尤其检查近义包名**：`extract` vs `extraction` 这类事故已发生过）。新建包前必须能在 §0.2 结构图里指认它的位置。
3. **先复用后新建**：写工具方法前先搜索是否已有（`Grep` 方法签名关键词）。已有方法不满足需求时优先**重载 / 扩展参数**，禁止复制一份改名。
4. **禁止死代码**：不留注释掉的代码块、不留「以后可能用得上」的方法、不建空包。删就是删（git 有历史）。
5. **禁止半成品**：一次生成的代码必须编译通过、测试通过。`TODO` 只允许用于「明确的待人工补全点」（如草稿生成的占位），且必须带说明。
6. **改 A 不顺手改 B**：一次改动只做一件事。发现相邻代码的问题，先提出来、经确认再修，禁止夹带。
7. **示例口径一致**：本项目的示例数据（资方名、URL、字段值）必须与 `docs/gen_sample_docs.py` 生成的虚构文档口径一致，禁止随手编新值造成口径分裂。

## 5. 异常、参数校验与日志

1. **禁止吞异常**：catch 后不处理不上抛 = 违规。要么处理（降级 / 兜底），要么包装语义后上抛。
2. 所有对外接口入参**必须校验**，非法值拒绝并给默认兜底，**禁止直接抛 500**（本项目教训）。
3. 统一 `@RestControllerAdvice` 全局异常处理；业务失败抛 `BizException(ErrorCode)`，禁止用裸 `RuntimeException` 做流程控制。
4. **降级路径必须留痕**：模型调用失败必须经 `LlmCallLogger` 记 `FAIL` + `error_msg`（旁路降级，留痕失败不挡主链路）。
5. 日志用 SLF4J 占位符 `log.info("xx {}", val)`，禁止字符串拼接；异常必须 `log.error("上下文", e)` 带堆栈。**禁止** `e.printStackTrace()` 与 `System.out`。
6. 日志级别：ERROR=需要人处理；WARN=自动恢复但有异常信号；INFO=关键流程节点；DEBUG=细节（生产不输出）。
7. 日志**禁止打印敏感信息**（身份证 / 银行卡 / 密钥）；关键上下文带 `docId` / `chunkId` / 请求 id。
8. 对外错误响应统一 `code` / `message`，**不暴露堆栈、内部类名、SQL**。

## 6. 数据访问（统一 MyBatis）

> **口径**：数据访问**统一 MyBatis**（取代早期 JdbcTemplate 直写方案）。pgvector 用法不变，载体由 Store 的 JdbcTemplate 改为 Mapper XML。

1. SQL **只存在于 `mapper/` 层**；禁止 service / controller 出现 SQL 字符串。
2. Mapper 接口 `XxxMapper` + `resources/mapper/XxxMapper.xml`；开启 `map-underscore-to-camel-case=true`。
3. **参数占位铁律**：一律 `#{}`，**严禁 `${}`**（拼接 = 注入；Tool 的参数来自模型，属外部输入，风险最高）。
4. 禁止 `SELECT *`，明确列名；动态条件用 `<where>` + `<if>`；重复 SQL 片段抽 `<sql>` + `<include>`。
5. **分页**：手写 `LIMIT #{limit} OFFSET #{offset}`，总数另查 `COUNT(*)`；**不引分页插件**；分页逻辑重复 ≥3 处再抽公共方法。
6. **结果映射**：返回 PO / DTO 并显式 `<resultMap>`；用 `record` 时须配 `<constructor>` 或开 `argNameBasedConstructorAutoMapping`。**禁止返回 `Map<String,Object>`**。
7. **pgvector 专用语法只允许出现在 Mapper XML**：向量以字符串（如 `"[0.1,0.2,...]"`）传入，SQL 里 `CAST(#{vec} AS vector)`；距离排序 `ORDER BY embedding <=> CAST(#{vec} AS vector)`；关键词相似度 `SIMILARITY(content, #{kw})`。不得泄漏到 service。
8. **幂等写入用 upsert**（`ON CONFLICT ... DO UPDATE`，项目惯例）；**先删后插的场景必须在注释说明为什么不用 upsert**。
9. 新表必须有 `COMMENT ON` 说明用途；**schema 变更必须同步回写 `docs/` 下的 schema SQL**（唯一建表事实源）——手动 psql 补的东西不回写 = 下一台全新环境必挂（历史事故：`CREATE EXTENSION vector` 没回写，compose 全新库直接报 `type vector does not exist`）。
10. 禁止循环内单条查 DB（N+1）；批量写用 `<foreach>` + `ExecutorType.BATCH`。
11. Mapper 只做取数，业务判定留在 Service；**禁止在 Mapper 上加 `@Transactional`**。

## 7. 事务

- 事务边界在 **Service** 方法：`@Transactional(rollbackFor = Exception.class)`。
- **非原子的「删+插」「多表写」必须加事务**（本项目教训）。
- 事务内禁止长耗时操作（模型调用、文件 IO）与循环内远程 / DB 调用；必要时拆成两段。

## 8. 并发、异步与集合

1. **禁止用 `Executors` 创建线程池**（`FixedThreadPool` / `CachedThreadPool` 无界队列有 OOM 风险）→ 用 `ThreadPoolExecutor` 显式指定参数，或 Spring 异步设施。线程池必须**命名前缀 + 有界队列 + 明确拒绝策略 + `@PreDestroy` 关闭**。
2. **SSE**：立即 `return emitter`，检索 / 生成 / `send` 放独立线程（本项目教训：否则假流式 + `ERR_ABORTED`）。
3. 多线程共享的 `SimpleDateFormat` / `ArrayList` / `HashMap` **禁止作为成员变量**；时间格式化用线程安全的 `DateTimeFormatter`。
4. `foreach` 中禁止增删元素（`ConcurrentModificationException`），用 `removeIf` 或 Iterator。
5. 集合转 Map 注意 key 冲突：`Collectors.toMap` **必须显式指定 merge function**（除非能证明 key 唯一并注释说明）。
6. 高并发计数用 `AtomicInteger` / `LongAdder`，不用同步块包 `i++`。

## 9. 常量、配置与密钥

- 禁止魔法值（见 §3.3）；可配置项走 `@Value` / 配置类，集中在 `application.yml`。
- 新增配置项**必须给默认值且可开关**（如 `rag.search.max-distance` 默认关闭）。
- 密钥 / 口令走**环境变量**，禁止硬编码进仓库；占位假值须显式标注。
- 新增第三方依赖须评估（能不加就不加），并在 §0.1 登记「选它 / 不选它」的理由。

## 10. 单元测试规约

1. **AIR 原则**：Automatic（自动化，无人工介入）、Independent（互相独立，无顺序依赖）、Repeatable（可重复，不依赖外部环境）。
2. 单测**不连真库**：mock Mapper / mock 邻居 Bean；**但用真实对象优于过度 mock**（历史教训：把 `LlmCallLogger` 整个 mock 掉，`record()` 变空壳，编排契约测不到——正确做法是真实 logger + mock 数据访问）。
3. 一个测试只验证一个行为；三段式 given / when / then；断言带消息说明意图。
4. **断言值必须手算核对**（历史教训：RRF 分数断言算错，把对的代码判成错的）。
5. 测试优先级：领域核心逻辑（RRF、抽取规则、熔断状态机）> 编排 > controller（薄，可暂缓）。
6. **bug 修复必须先补一个能复现该 bug 的 RED 测试**，再修复转绿。
7. 新增 / 修改公共方法必须补或改单测；**改签名 / 改同步异步契约必须同步测试**（含 `src/test`）。

## 11. 技术债与迁移清单

> 规范默认**只约束增量**；存量 `Map<String,Object>` 返回体、无接口 Service、缺失分层不强制改造（改动触及时「改哪算哪」）。
> **唯一例外：数据访问层以「全量 MyBatis」为目标。** 一次 commit 只治一项。

**数据访问迁移（MyBatis 化）**

- [ ] `rag/store/DocumentStore`（JdbcTemplate）→ `rag/mapper/DocChunkMapper`（+ `resources/mapper/DocChunkMapper.xml`）
- [ ] `extract/store/FieldMappingStore` → `extract/mapper/FieldMappingMapper`
- [ ] `resilience/LlmCallLogger`（JdbcTemplate 留痕）→ `resilience/mapper/LlmCallLogMapper`
- [ ] 迁移要求：**一处一次迁完**，不留「半个方法 JdbcTemplate、半个 Mapper」；迁移后既有测试基线（如 `SearchServiceTest`）必须仍通过。

**包结构迁移（按领域归位；迁移前新 Controller 禁止再进顶层包）**

| 现位置 | 目标位置 |
|---|---|
| `controller/QaController` | `rag/controller` |
| `controller/EvalController` | `rag/controller`（评估逻辑并入 `rag`；`rag/eval/` 保留评估服务与资源） |
| `controller/DocumentAdminController` | `rag/controller` |
| `controller/ExtractController` | `extract/controller` |
| `controller/DraftController` | `draft/controller` |
| `controller/ChatController` + `service/ChatService` | 待定（阶段0连通件；候选：并入 `rag/service` 或删除——**删除需用户确认**） |
| `controller/HealthController` | 待定（候选：`common/` 或删除——**删除需用户确认**） |
| `rag/qa/QaService` 等 | `rag/service` |
| `rag/eval/EvalService` | `rag/service`（`evalset.json` 资源位置不变） |
| `extract` 下 `*Service` / `*Generator` / `InternalFieldCatalog` | `extract/service` |
| `FieldMapping` / `InternalField` | `extract/model` |

- [ ] 迁移完成后删除顶层 `controller/`、`service/` 空包，此后**禁止再创建**。

**代码治理**

- [ ] 魔法值散落：场景名 `"qa"` / `"extract"` / `"draft"`、超时秒数、置信度 `0.9` / `0.7` → 归 `common/constant/`
- [ ] `common/constant/`、`common/util/` 首次需要时按 §0.2 创建
- [ ] controller 返回 Map 手拼键名 → 收敛为 `record`
- [ ] `QaService` 的 `Executors.newFixedThreadPool` → 改 `ThreadPoolExecutor` 显式参数（§8.1）

## 12. 提交纪律

1. commit 前必须 `mvn test` 全绿；**测试数量下降必须说明原因**。
2. commit message 沿用现有风格：`阶段X(partY): 主题`，另带要点列表。
3. 禁止 `git add -A` 无差别提交无关文件；`app.log`、`target/` 不入库（已在 `.gitignore`）。
4. 一次改动聚焦一个目标，不混多主题；架构级取舍先写 `docs/`。

## 13. 禁止清单（红线）

❌ `System.out` / `e.printStackTrace()`　❌ 吞异常 / 空 catch　❌ 拼接 SQL / `${}` / `SELECT *`
❌ `Map<String,Object>` 作跨层或对外契约　❌ 魔法值 / 硬编码密钥　❌ 用 `Executors` 建线程池
❌ 无 HTTP 入口的包建 `controller/`　❌ 顶层新建 `controller/` `service/` `mapper/` 技术包
❌ 空包 / 死代码　❌ 业务代码里 `if-else` 判 mock / deepseek　❌ 复制粘贴改名（不抽公共方法）
❌ 改签名不同步调用方与测试　❌ JPA / Hibernate / MyBatis-Plus / Lombok / WebFlux

## 14. 提交前自检 Checklist

- [ ] 已搜过并复用现有公共方法 / SQL 片段（无重复实现）
- [ ] 新类已归位（领域 + 层），未新建平行 / 近义包
- [ ] 入参有校验与默认兜底；异常走全局处理，无吞异常
- [ ] SQL 走 `#{}` 占位、显式列名、无 `SELECT *`；分页 `LIMIT/OFFSET`
- [ ] pgvector 语法只在 Mapper XML；结果映射返回 PO/DTO，无裸 `Map`
- [ ] 跨层传值用 `record`（增量）
- [ ] 日志用占位符、无敏感信息
- [ ] 非原子写有事务；线程池显式参数 + 可关闭；SSE 立即 return
- [ ] schema 变更已回写 `docs/` schema SQL
- [ ] 改签名已同步全部调用方与测试
- [ ] `mvn test` 全绿
