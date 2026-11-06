# 代码结构治理规范 · 包结构与类命名重构

> 状态：草案 v0.5（2026-11-06 二次决策：`tools/` → `aitools/` 保留；`rag/context` 回退为 `rag/tool`，类名恢复 `*Tool`——`ContextBuilder` 与类实际职责不符。阶段 1 的 1.1–1.7、阶段 2 测试镜像、阶段 3 均已完成）
> 关联文档：`docs/agents/coding-standards.md` §0（包结构）/ §2（命名）、`GLOSSARY.md`
> 日期：2026-11-06
> 原则：**本次治理只做"归类与改名"，不改任何业务逻辑**。每一步迁移后必须编译 + 全量测试通过才进入下一步。

---

## 1. 目的与范围

当前代码按"业务领域包 + controller/service/mapper 三层"组织，方向正确，但随着领域增多，出现了以下退化：

1. **model 包成为"垃圾抽屉"**：表投影、落库入参、领域对象、检索结果、HTTP 契约混在一个包里，命名后缀不统一（`Row` / `Insert` / `Raw` / 无后缀并存）。
2. **对外契约包（`vo/`）与 `model/` 职责边界模糊**，且各领域的 vo 使用程度不一致。
3. **rag 领域过大**，内部 plan / routing / tool / eval 各自成小王国，包结构与兄弟包不一致。
4. **近义包**：顶层 `tools/` 与 `rag/tool/` 名字近似（阶段 2 以 `tools/` → `aitools/` 消歧；`rag/tool/` 维持原名，边界按 ADR-0012 保留）。
5. **测试类摆放错位**：部分测试类的包路径与被测类不一致，部分测试类名与被测类无对应关系。
6. **resources 下存在 `.java` 文件**（草稿生成模板），容易被 IDE / 静态工具误认为源码。

本文档给出：目标包结构规范（§3）、逐类迁移映射表（§4）、rag 拍平决策与工具面命名（§5）、测试摆放规范（§6）、分阶段执行计划（§7）。

**不在范围内**：业务逻辑重构、接口契约变更、数据库 schema 变更。

---

## 2. 现状问题清单（带证据）

| # | 问题 | 证据 | 严重度 |
|---|------|------|--------|
| P1 | model 包混放多角色对象，后缀 4 种风格并存 | `conversation/model/`：`ConversationRow`（表投影）/ `MessageInsert`（落库入参）/ `ConversationRound`（SSE 回传）/ `QaSourceSnapshot`（JSONB 快照）同包；`rag/model/`：`ChunkRow`/`ChunkItemRow`（表投影）与 `Chunk`/`DocContent`/`VectorHit`（领域/结果对象）同包 | 高 |
| P2 | 对外契约与领域对象边界靠约定不靠结构 | `rag/vo/request`、`rag/vo/response` 存在，但 `rag/model/ChatTurn` 也进 prompt 组装链路；`conversation/vo/` 只有 response 半边 | 中 |
| P3 | rag 领域内部子域结构不一致 | `rag/plan/` 自带 `model/`+`impl/`，service 类（`PlanAndExecuteService`、`PlanBudget`、`PlanClock` 等 8 个）平铺在包根；`rag/routing/` 同样模式 | 中 |
| P4 | 近义包 | 顶层 `tools/` 与 `rag/tool/` 名字近似 | 已解决（阶段 2 将 `tools/` 改名 `aitools/`；`rag/tool/` 保持原名） |
| P5 | strategy 包混入非策略类 | `rag/strategy/AnswerCancellation`、`rag/strategy/AnswerGenerationRequest`——它们是数据对象不是策略 | 中 |
| P6 | 三个近义类并存，语义重叠 | `extract/model/FieldMapping` / `FieldMappingRow` / `FieldMappingRaw` | 中 |
| P7 | 测试包路径与被测类不一致 | `conversation/SchemaContractTest`、`conversation/MapperXmlContractTest` 堆在 conversation 包根；`rag/service/MockToolAnswerTest` 测的是 `rag/tool` 的内容 | 中 |
| P8 | resources 下的 `.java` 文件易混淆 | `src/main/resources/draft/LenderHandler.java`、`ExampleLenderHandler.java`（草稿生成模板素材，声明了 `package com.lender.draft.generated`，不参与编译） | 低 |
| P9 | 横切包命名不统一 | `common/dto/ApiResponse`——`common` 包下出现 `dto`，与新规范的 dto 语义（层间传输对象）冲突；它是 web 层统一响应契约 | 低 |

---

## 3. 目标包结构规范

### 3.1 领域内四类模型包

每个业务领域包内，模型类按**角色**归入四个子包，类名后缀与包一一对应：

```text
<domain>/
├── controller/              # HTTP 入口（*Controller）
├── service/                 # 业务接口 + impl/（*Service / *ServiceImpl）
├── mapper/                  # MyBatis Mapper 接口（+ resources/mapper/<X>Mapper.xml）
├── strategy/                # 可替换策略接口 + impl/（*Strategy）
└── model/
    ├── entity/              # 表映射对象，后缀 Entity。一个类对应一张表（或一张表的读/写投影）
    ├── request/             # HTTP 入参，后缀 Request。仅 controller 层使用
    ├── response/            # HTTP 出参，后缀 Response。仅 controller 层使用
    └── dto/                 # 其余层间传输对象，后缀 DTO。service↔mapper、service↔service、SSE 载荷等
```

### 3.2 归类判定决策树

拿到一个模型类，按顺序问：

```text
1. 它是否只出现在 Controller 的入参/出参（或其嵌套）？
   是 → model/request/ 或 model/response/，后缀 Request / Response
2. 它是否被 Mapper 接口直接读写（@Select/@Insert 参数或结果、XML resultType/parameterType），
   且字段与数据库表列一一对应？
   是 → model/entity/，后缀 Entity
3. 其他一切跨层/跨服务传递的数据载体（落库参数包、检索命中、解析结果、SSE 载荷、
   JSONB 快照元素……）
   → model/dto/，后缀 DTO
```

**补充规则：**

- **Entity 是"表的样子"，DTO 是"流程的样子"。** 判断标准不是"它像不像实体"，而是"谁在用它"：Mapper 直接消费 → Entity；Service 之间传递 → DTO。同一张表若有"读取投影"和"完整行"两个类，都属于 Entity（如 `ConversationRow` 的两种形态），不要为读投影单开后缀。
- **request/response 只允许被 controller 和 service（接口层实现）引用**，mapper、strategy 不得 import 它们。发现 mapper 使用 response 类即为违规（当前 `QaSourceSnapshot` 同构复刻 `QaSourceResponse` 的做法是正确的解法，保留）。
- **禁止在 `model/` 根下直接放类**。模型类必须落进四个子包之一，`model/` 本身不再直接承载类，杜绝"随便放"复发。
- **四层只收"数据载体"**（带状态的 record/POJO）。枚举、异常、策略接口、工具类、配置类不参与归类：枚举暂时跟随其主数据载体所在包（如 `StepStatus` 随 plan 流程载体进 `model/dto/`，**不加后缀**），同领域凑满 2 个枚举再独立 `<domain>/enums/`（避免单类包）；常量维持 `common/constant/`（横切）。
- **SSE 载荷按"主要用途"判，不按传输协议判**。为 SSE 事件而生、只为出口的载荷与 REST 出参同类——都是"给前端的展示对象"，大厂惯例同此（阿里《Java 开发手册》的 VO 定义不区分 REST/SSE，Spring 官方也将 SSE event data 作为 response body 处理），现有 `QaPlanResponse` / `QaStepResponse` / `QaSourceResponse` 即属此类，迁 `model/response/`。反过来，**存进 JSONB 供历史回放、或本身是流程载体只是被 SSE 顺带复述的内部对象**（如 `QaSourceSnapshot`、`ConversationRound`）归 dto。判断依据是"主要为出口而生"还是"流程载体"，不是"它有没有被 SSE 发出去"。
- **跨领域共享的 dto**：放"提供方"领域的 `model/dto/` 并在 javadoc 声明共享（如 `FieldMapping` 被 extract 与 draft 共用，留 extract）。凑满 3 个消费领域再考虑抽公共模型包，当前不建顶层 shared/model（避免过早抽象）。
- **dto 包天然是最大的子包，这是正常的**——它是"流程的样子"的集合。失控标准：单包超过约 30 个类时按用途做包内分组（届时再评审，不在本次范围），而不是发明第五个层。

### 3.3 横切包（common）的处理

`common` 不是业务领域，不套用上面的四类结构：

- `common/dto/ApiResponse` → **迁移为 `common/web/ApiResponse`**。它是 web 层统一响应契约（由 `ApiResponseBodyAdvice` 包装），归属 `common/web/` 与其消费者（Advice / Handler / Interceptor）同包语义。
- `common/` 下**新增禁止出现 `dto/` 子包**，避免与领域内 `model/dto/` 语义打架。

### 3.4 顶层包结构目标（阶段一收敛后）

```text
com.lender.assistant
├── conversation/    # 会话领域
├── extract/         # 字段抽取领域
├── draft/           # 草稿生成领域
├── rag/             # RAG 领域（拍平决策见 §5）
├── resilience/      # 模型调用熔断与留痕
├── aitools/         # 模型可自主调用的 Function Calling 工具（与 rag/tool 分离，见 §5.3）
├── ops/             # 健康检查
├── common/          # annotation / constant / exception / util / web
└── LenderAssistantApplication.java
```

---

## 4. 迁移映射表（逐类）

> 格式：`现状 → 目标`。加粗的是**类名也要改**的；其余仅挪包。
> 所有类的归属已裁决完毕，无遗留待确认项；执行时不需要再做设计决策。

### 4.1 conversation 领域

| 现状 | 目标 | 说明 |
|---|---|---|
| `conversation/model/ConversationRow` | **`conversation/model/entity/ConversationEntity`** | conversation 表读取投影 |
| `conversation/model/MessageRow` | **`conversation/model/entity/MessageEntity`** | message 表读取投影 |
| `conversation/model/MessageInsert` | **`conversation/model/dto/MessageInsertDTO`** | 落库入参，非表形态 |
| `conversation/model/AssistantMessageCompletion` | **`conversation/model/dto/AssistantMessageCompletionDTO`** | 终态落库入参 |
| `conversation/model/ConversationRound` | **`conversation/model/dto/ConversationRoundDTO`** | 受理线程→SSE 传输 |
| `conversation/model/QaSourceSnapshot` | **`conversation/model/dto/QaSourceSnapshotDTO`** | JSONB 快照元素 |
| `conversation/vo/response/*`（4 类） | `conversation/model/response/*` | 仅挪包，类名不变 |
| `conversation/controller|service|mapper/*` | 不动 | |

### 4.2 extract 领域

| 现状 | 目标 | 说明 |
|---|---|---|
| `extract/model/FieldMapping` | **`extract/model/dto/FieldMappingDTO`** | 非表列集：多派生字段 internalFieldName（来自 InternalFieldCatalog），无 id/docId/status；Mapper 不直接消费它；被 extract 与 draft 两个领域共用（§3.2 规则：共享 dto 留提供方） |
| `extract/model/FieldMappingRow` | **`extract/model/entity/FieldMappingEntity`** | field_mapping 完整行读取投影（列 1:1）；同时是 GET /api/extract/{docId} 响应元素——"Entity 直接出查询契约"为既成事实，为保 JSON 契约零变化**保留不拆**，在 javadoc 标注；未来响应要加展示字段时再拆 response 类 |
| `extract/model/FieldMappingRaw` | **`extract/model/entity/FieldMappingRawEntity`** | 同表的写入/窄读投影（列 1:1）：主用途 saveMappings 批量 upsert 参数，复用于 listForDraft 草稿取数。三胞胎**不合并**（§8） |
| `extract/model/InternalField` | **`extract/model/dto/InternalFieldDTO`** | 静态目录元素，非表 |
| `extract/vo/request/ConfirmRequest` | **`extract/model/request/ExtractConfirmRequest`** | 顺手修"裸 ConfirmRequest"这种过泛命名 |
| `extract/vo/response/*`（3 类） | `extract/model/response/*` | 仅挪包 |

### 4.3 draft 领域

| 现状 | 目标 |
|---|---|
| `draft/vo/response/DraftResponse` | `draft/model/response/DraftResponse` |
| `draft/controller|service|strategy/*` | 不动 |

### 4.4 rag 领域（阶段一：归类改名 + plan/routing 拍平）

| 现状 | 目标 | 说明 |
|---|---|---|
| `rag/model/ChunkRow` | **`rag/model/entity/ChunkEntity`** | chunk 表行 |
| `rag/model/ChunkItemRow` | **`rag/model/entity/ChunkItemEntity`** | chunk_item 表行 |
| `rag/model/Chunk` | **`rag/model/dto/ChunkDTO`** | 检索单元的流程载体 |
| `rag/model/DocChunkLine` | **`rag/model/dto/DocChunkLineDTO`** | |
| `rag/model/DocContent` | **`rag/model/dto/DocContentDTO`** | |
| `rag/model/DocSummary` | **`rag/model/dto/DocSummaryDTO`** | |
| `rag/model/ParsedDocument` | **`rag/model/dto/ParsedDocumentDTO`** | |
| `rag/model/KeywordHit` | **`rag/model/dto/KeywordHitDTO`** | |
| `rag/model/VectorHit` | **`rag/model/dto/VectorHitDTO`** | |
| `rag/model/ChatTurn` | **`rag/model/dto/ChatTurnDTO`** | |
| `rag/vo/request/*`（3 类） | `rag/model/request/*` | |
| `rag/vo/response/*` | `rag/model/response/*` | |
| `rag/strategy/AnswerCancellation` | **`rag/model/dto/AnswerCancellationDTO`** | 非策略，迁出 strategy |
| `rag/strategy/AnswerGenerationRequest` | **`rag/model/dto/AnswerGenerationRequestDTO`** | 非策略（answer 生成链的内部组装参数，非 HTTP 契约，故进 dto 不进 request） |
| `rag/strategy/*Strategy`（接口+impl+factory） | `rag/strategy/`、`rag/strategy/impl/` 原地保留 | 已符合规范 |
| `rag/controller|service|mapper/*` | 不动 | |

**plan 族拍平**（取消 plan 子包，决策见 §5.1）：

| 现状 | 目标 | 说明 |
|---|---|---|
| `rag/plan/model/` 7 类（ExecutionPlan、ExecutionPlanSnapshot、PlanExecutionResult、PlanStep、StepEvent、StepOutcome、StepStatus） | **`rag/model/dto/*DTO`** | StepStatus 是枚举，保留原名不加后缀（§3.2 补充规则） |
| `rag/plan/PlanAndExecuteService`、`PlanBudget`、`PlanClock`、`PlanSettings`、`PlanSourceAggregator`、`StepListener`、`SynthesisPromptBuilder` | `rag/service/` 原名 | Plan* 前缀保语义 |
| `rag/plan/PlannerModelClient` | `rag/client/PlannerModelClient` | 模型网关层（§5.2） |
| `rag/plan/impl/MockPlannerModelClient`、`OpenAiPlannerModelClient` | `rag/client/impl/` | |

**routing 族拍平**（取消 routing 子包）：

| 现状 | 目标 |
|---|---|
| `rag/routing/model/QuestionRoute`、`RoutingDecision`、`RoutingRequest` | **`rag/model/dto/`**：`RoutingDecisionDTO`、`RoutingRequestDTO`；`QuestionRoute` 是枚举，**原名保留不加后缀**（§3.2 补充规则） |
| `rag/routing/QuestionRoutingService`、`PreviousRouteResolver` | `rag/service/` |
| `rag/routing/RoutingModelClient` | `rag/client/` |
| `rag/routing/impl/QuestionRoutingServiceImpl` | `rag/service/impl/` |
| `rag/routing/impl/MockRoutingModelClient`、`OpenAiRoutingModelClient` | `rag/client/impl/` |

消费者影响：已核实 `rag.plan` / `rag.routing` 在 rag 之外**无引用方**，引用仅来自 `QaServiceImpl`、`RoutingEvalServiceImpl` 与 `vo/response`（QaPlanResponse / QaStepResponse），import 随迁即可，拍平对外零影响。

**rag/tool**：阶段一原地不动；阶段二维持 `rag/tool/` 与 `*Tool` 原名（曾短暂改为 `rag/context/` + `ContextBuilder`，已回退，理由见 §5.3）。

### 4.5 resilience / tools / common

| 现状 | 目标 | 说明 |
|---|---|---|
| `resilience/model/LlmCallLogRecord` | **`resilience/model/entity/LlmCallLogEntity`** | llm_call_log 表 |
| `resilience/ResilientLlmExecutor`、`LlmCallLogger` | `resilience/` 根保留（仅 2 个顶层类，不强行造 service 层） | |
| `tools/LenderQueryTools`、`ToolIntentRouter` | **`aitools/`**（仅改包名，类名不变） | |
| `rag/tool/AgentTool`、`ToolContext`、`ToolErrorCode`、`ToolResult`、`PlanToolRegistry`、`tool/impl/*`（3 类） | **原地不动**（`rag/tool/`，类名维持 `*Tool`） | |
| `common/dto/ApiResponse` | **`common/web/ApiResponse`** | 见 §3.3 |
| `common/*` 其余 | 不动 | |

### 4.6 resources 资源文件

| 现状 | 目标 |
|---|---|
| `src/main/resources/draft/*.java` | **改后缀为 `*.java.tmpl` 并移至 `src/main/resources/templates/draft/`**。草稿生成器读文件时同步改路径。消除"resources 里有源码"的歧义（这两个文件声明 `package com.lender.draft.generated` 却永不编译，IDE 会标红、静态扫描会误报） |
| `src/main/resources/mapper/*.xml` | 不动（`mapper-locations: classpath:mapper/*.xml` 不变）；但 XML 内的 `resultType`/`parameterType` 全限定名必须随 Java 迁移同步更新 |

---

## 5. rag 结构决策：拍平，不建子域

### 5.1 决策与理由

（2026-11-06 评审决策：**不建子域**。原"rag 内拆 qa / ingest / plan / routing / eval 子域"方案作废，plan/routing 按标准分层拍平，逐类目标见 §4.4。）

理由：

- plan/routing 的"子域感"来自它们自带的 `model/` 目录和包根平铺的 service 类，按标准分层归位后自然消解，**不需要引入"领域中的领域"这一层概念**——分层是全项目统一的一套，子域却会催生第二套结构；
- 二者的消费者（`QaServiceImpl`、`RoutingEvalServiceImpl`）本来就在 `rag/service/`，拍平后同层依赖更直接；已核实 `rag.plan` / `rag.routing` 在 rag 之外无引用方，拍平无外部影响；
- 类名自带 `Plan*` / `Routing*` / `QuestionRoute*` 语义前缀，拍平不损失检索性；
- 拍平后 `rag/model/dto` 共 20 个类（`rag/model` 现有 10 个 + plan 6 个 DTO + 1 个枚举 `StepStatus` + routing 2 个 DTO + 1 个枚举 `QuestionRoute`），规模可控（失控标准见 §3.2 补充规则）。

### 5.2 拍平后的 rag 目标结构

```text
rag/
├── controller/        # QaController / DocumentAdminController / EvalController
├── service/           # Qa / Search / Document / Eval / RoutingEval 业务服务
│                      #   + Plan 编排族（PlanAndExecuteService、PlanBudget、PlanClock、
│                      #     PlanSettings、PlanSourceAggregator、StepListener、SynthesisPromptBuilder）
│                      #   + routing 族（QuestionRoutingService、PreviousRouteResolver）（+impl/）
├── client/            # 模型网关：PlannerModelClient / RoutingModelClient（+impl/，mock/openai）
├── mapper/            # DocChunkMapper
├── tool/              # 编排层计划工具 SPI（*Tool；与 aitools 的模型工具面分离，见 §5.3）
├── strategy/          # parser / splitter / embedding / answer 四维策略（+impl/ +factory/）
└── model/
    ├── entity/        # ChunkEntity / ChunkItemEntity
    ├── request/       # *Request
    ├── response/      # *Response
    └── dto/           # Chunk / DocContent / VectorHit / … + plan/routing 流程载体（约 21 类）
```

关于新增的 `client/` 层：`PlannerModelClient` / `RoutingModelClient` 是**模型网关**——封装对外部系统（LLM API）的访问，定位与 mapper（封装外部 DB）同级，不是业务 Service，也不是可替换算法的 strategy（它们没有"算法可替换"语义，只有"供应商可替换"）。放 `service/` 会与业务 Service 混淆，故单设技术层。这是**层**不是子域，符合"不要子域"的约束；若后续其他领域也出现模型网关，再上提为跨领域约定。

### 5.3 工具面命名：两个包名，按职责区分

（2026-11-06 二次决策：**不合并、不改类名**。ADR-0012 的边界结论保留——`aitools/` 是模型可自主调用的 Function Calling 面，`rag/tool/` 是编排层按计划执行的工具 SPI。）

目标结构：

```text
aitools/                  # 模型可自主调用（@Tool）
├── LenderQueryTools
└── ToolIntentRouter

rag/tool/                 # 编排层计划工具（维持 *Tool 原名）
├── AgentTool
├── ToolContext
├── ToolResult
├── ToolErrorCode
├── PlanToolRegistry
└── impl/
    ├── KbSearchPlanTool
    ├── MockCustomerProfileTool
    └── MockLenderProductTool
```

判断依据：两类工具的消费者不同——`aitools/` 由模型在 Function Calling 中自主调用，`rag/tool/` 由 `PlanAndExecuteService` 按计划顺序调用；失败语义、超时与观测单位也不同（ADR-0012）。物理同包会弱化这条边界，因此保持两包分离，仅以包名与职责区分。曾尝试把 `rag/tool/` 改名为 `rag/context/` 并给类名补 `ContextBuilder` 后缀，因后缀与类实际职责不符（执行一次工具调用并返回结果，不构建上下文）已回退。

---

## 6. 测试摆放规范（回答"测试类放业务包下正常吗"）

### 6.1 结论：镜像结构是标准做法，本身合理

`src/test/java` 与 `src/main/java` **同包镜像**是 Maven/Java 社区的标准约定，本项目遵循它是**正确的**，不要改成"所有测试集中到一个 test 包"。理由：

1. 同包才能访问被测类的包级可见成员（package-private），不必为了测试把生产代码改成 public；
2. 找测试 intuitive：`FooService` 的测试就在同路径的 `FooServiceTest`；
3. 构建工具默认识别 `src/test/java`，打包时自动排除。

**不合理的从来不是"测试在业务包下"，而是下面三种错位。**

### 6.2 需要修正的三种错位

| 错位 | 现状例子 | 修正 |
|---|---|---|
| 测试包路径 ≠ 被测类包路径 | `conversation/MapperXmlContractTest` 堆在 conversation 包根（已核实：只覆盖 ConversationMapper / MessageMapper 两个 XML） | 移到 `conversation/mapper/`（与 Mapper 接口同包），改名 `ConversationMapperXmlContractTest` |
| 跨领域测试误挂单一领域 | `conversation/SchemaContractTest`——已核实它读的是全局 `docs/schema.sql`（覆盖 doc_chunk / field_mapping / llm_call_log / conversation / message 等跨领域表），不属于任何单一领域 | 移到测试根包 `com.lender.assistant`，类名 `SchemaContractTest` 保留；此类"全局事实源契约测试"今后都放根包 |
| 测试类名与被测类无对应 | `rag/service/MockToolAnswerTest`（历史：测的是工具相关行为，却挂在 service 包） | 已迁至 `aitools/`（与被测的 `LenderQueryTools` / `ToolIntentRouter` 同包）；今后按被测类命名（`<被测类>Test`），流程级测试保留 `*FlowTest` 后缀 |
| 测试目录里出现与 main 不对应的包 | 阶段二工具面改名等结构调整后 | 测试目录同步镜像新包结构，迁移与主代码同一 commit |
| 测试包随 plan/routing 拍平 | `rag/plan/*Test`（6 个）、`rag/routing/*Test`（2 个）留在已不存在的包 | 1.6 内随被测类迁到 `rag/service/`、`rag/service/impl/`、`rag/client/impl/`、`rag/model/dto/` 对应镜像包，测试类名不变 |

### 6.3 测试命名规范（汇总，与 coding-standards §2.5 对齐）

- 单元测试：`<被测类>Test`，与被测类同包；
- 流程/集成测试：`<场景>FlowTest`，放流程入口类所在包；
- 契约测试：`<被测对象>ContractTest`，与被测对象同包；
- 测试目录**禁止**出现 main 中不存在的"平行顶层包"（如自创 `testutil/` 之外的包）；公共测试基建放 `src/test/java/com/lender/assistant/testutil/`（当前不存在则不新建，需要时再建）。

---

## 7. 分阶段执行计划

> 每一步的完成定义：`mvn -q clean compile` 通过 → `mvn test` 全绿 → 独立 commit。
> 中途任何一步红灯：`git restore` 回退该步，排查后再来。**禁止跳步合并 commit**——出问题时无法定位是哪次挪包引入的。

### 阶段 0：基线（0.5h）

0. **执行前必读**：`docs/agents/coding-standards.md`（现行编码规范）与 `GLOSSARY.md`（领域术语）——本文档与其冲突处以本文档为准（阶段 1.7 会同步修订前者）；本文档即完整执行依据，无需其他会话上下文；
1. `git status` 确认工作区干净；
2. 跑一次全量测试确认基线为绿，记录测试数（2026-11-06 实测基线：42 个测试类 / **248** 个用例，全绿；文中旧称"240"作废）；
3. `mvn clean` 清掉 `target/`，避免增量编译掩盖挪包问题。

### 阶段 1：包内归类与改名（低风险，纯机械）

按依赖从少到多的顺序，**每个领域一个 commit**：

| 步骤 | 内容 | 验证要点 |
|---|---|---|
| 1.1 | common：`dto/ApiResponse` → `web/ApiResponse` | 全局异常处理、Advice 相关测试 |
| 1.2 | resilience：`LlmCallLogRecord` → `model/entity/LlmCallLogEntity` | `LlmCallLogMapper.xml` 无全限定名引用（已核对），只改 Java import；跑 `LlmCallLogger`/`ResilientLlmExecutor` 相关测试 |
| 1.3 | conversation：§4.1 全部 | `ConversationMapper.xml`、`MessageMapper.xml` 同步；`QaSourceSnapshotContractTest` 随迁 |
| 1.4 | extract：§4.2（三胞胎已裁决：只归类改名，不合并） | `FieldMappingMapper.xml` 同步 |
| 1.5 | draft：§4.3 + resources 模板改后缀移目录（`draft/*.java` → `templates/draft/*.java.tmpl`） | 必须同步改两个读取方的 **`getResourceAsStream` 字面量**：`MockDraftGeneratorStrategy` 与 `OpenAiCompatibleDraftGeneratorStrategy`（当前均为 `"/draft/ExampleLenderHandler.java"`）；跑 `DraftServiceTest` 及两个策略测试 |
| 1.6 | rag：§4.4（model 归类 → strategy 清理 → plan/routing 拍平进标准分层） | `DocChunkMapper.xml` 同步；plan/routing 测试随迁 |
| 1.7 | 全量回归 + 修订 `docs/agents/coding-standards.md` §0/§2、`GLOSSARY.md` 代码锚点 | 与 1.1–1.6 已落地的结构对齐。**必须重写的现行条款**：§0.1 结构树（`vo/request|response` → `model/request|response`，`model/` 根不再直接放类，`rag/` 增 `client/`）、§0.2「对外契约一律进 `vo/`」与「`model/` 只放跨层 record」两条要改为 §3.1 的四分包规则、§1 分层表补 `client/`（模型网关）一行。修订与 1.7 的代码改动同一个 commit |

操作要点：

- 用 IDE 的 Move / Rename 重构（自动改 import），不要手改；
- Java 文件用 `git mv` 语义等价于 IDE 重构后的暂存，确保 `git log --follow` 可追溯；
- **`type-aliases-package` 未配置**，XML 只认全限定名；已核对的 9 处 `resultMap type` 必须逐步骤同步（迁移哪个领域改哪几行）：
  `ConversationMapper.xml:10`（ConversationRow）、`MessageMapper.xml:11`（MessageRow）、
  `DocChunkMapper.xml:15/26/34/44/56`（DocSummary / DocChunkLine / ChunkItemRow / VectorHit / KeywordHit）、
  `FieldMappingMapper.xml:16/29`（FieldMappingRow / FieldMappingRaw）；
- XML 里的全限定名逐文件 grep 旧包名确认清零（模式按该步骤实际迁移的旧包名扩展）：
  `grep -rn "assistant.rag.model\|assistant.conversation.model\|..." src/main/resources/`
- 迁移完成后 `grep -rn "\.model\.[A-Z]" src/main/java` 抽查是否有直接引用 `model` 根的残留 import；
- **回滚口径**：每步一个 commit，红灯时 `git revert <该步 commit>` 回到上一步，禁止 `reset --hard`、禁止 amend 覆盖已推送历史以外的后续步。

### 阶段 2：结构级调整

1. 已完成：`tools/` → `aitools/`；`rag/tool/` 维持原名与 `*Tool` 类名（曾短暂改为 `rag/context/` + `ContextBuilder`，已回退）；
2. 测试目录同步镜像 + §6.2 错位修正（1.3 / 1.6 与本次改名已随迁完成，执行时复核）；
3. 若 `rag/model/dto` 增长超过阈值（§3.2 补充规则），届时再评审包内分组；同领域枚举凑满 2 个时独立 `<domain>/enums/`。

### 阶段 3：防复发

1. 已完成：`docs/agents/coding-standards.md` §0.3 增补四分包判定与后缀对照表（Entity / DTO / Request / Response，§3.1）；
2. 已完成：§10「提交前自检」增加一条「模型类是否落在正确四分包子包且后缀正确」；
3. **不做**（2026-11-06 决策）：暂不引入 ArchUnit 规则——新增测试依赖违背「新增依赖能不加就不加」，且后缀规则对枚举 / 接口 / 工具类不成立，规范 + 自检已覆盖。

---

## 8. 风险清单

| 风险 | 缓解 |
|---|---|
| MyBatis XML 中全限定类名与 Java 迁移不同步 → 运行期才炸 | 阶段 1 每步 grep 旧包名清零；`MapperXmlContractTest` 正好是现成的护栏 |
| 包名变化影响 Spring 组件扫描 | 扫描根是 `com.lender.assistant`，领域内挪包不影响；`common/dto→web` 仍在根下，安全 |
| JSON 序列化结果变化 | 挪包不改字段名，Jackson 默认不输出类名，安全；`QaSourceSnapshot` 类的"同构复刻"设计继续保留 |
| 反射 / `Class.forName` / 配置文件引用旧类名 | 迁移前 `grep -rn "Class.forName\|forName(" src/main/java` 确认（当前预期为 0） |
| resources 模板路径改动破坏草稿生成 | 步骤 1.5 中同步修改读取方并跑 `DraftServiceTest` |
| FieldMapping 三胞胎被误合并 | 已裁决（§4.2）：三个 record 对应的 MyBatis 列集各不相同，record 构造映射要求逐列集匹配，合并须改 SQL——超出"只挪包"红线，本次只归类改名 |

---

## 9. 红线（重申并补充）

1. 本次治理**只挪包、只改名**，不改业务逻辑、不改对外接口契约、不改 SQL；
2. 每步一个 commit，全量测试绿才进下一步，红灯即回滚；
3. `docs/agents/coding-standards.md` 与本文档冲突时，以**先合入并生效的那份修订**为准，另一份在同一 commit 内同步修订——不允许两份规范长期并存打架；
4. 挪包必须保留 git 历史（IDE 重构 / git mv），禁止"删了重写"；
5. `model/` 根下从此不允许直接放类（§3.2 补充规则）。
