# ADR-0018：移除模型调用 Mock，保留业务样例工具

- 状态：已采纳
- 相关：ADR-0010、ADR-0011、ADR-0012、ADR-0014、`rag/service/AnswerGeneratorService`、`extract/service/ExtractGeneratorService`、`draft/service/DraftGeneratorService`

## 背景

为让「没有 API key 也能跑通全链路」成立，项目早期给五处模型调用做了双实现：对话生成、问题路由、计划、抽取、草稿。每处用 `rag.chat.provider` / `rag.extract.provider` / `rag.draft.provider` 在 mock 与真实实现之间切换，编排层还用 `isMock()` 能力位在 mock 模式下跳过路由与计划。

引入真实 key 之后，这套双实现的成本显现：真实环境永远走真实模型，mock 分支长期无人验证；`isMock()` 让 `QaServiceImpl` 多出一层条件分支，路由、计划、事件序列出现「两种事实」。

需要划清一条界线：

> **调用 LLM 的实现是「模型 mock」（删）；本地确定性实现是「业务能力」（留）。**

`customer.profile` / `lender.product` 属于后者：它们读本地 JSON、不调模型，是可替换的业务数据源，不是模型替身。删掉它们等于删功能，不属于本次清理范围。

## 决策

**一、删除模型调用 mock，只留真实实现**

- 删除 `MockAnswerGeneratorStrategy`、`MockRoutingModelClient`、`MockPlannerModelClient`、`MockExtractGeneratorStrategy`、`MockDraftGeneratorStrategy`。
- 删除 `AnswerGeneratorStrategy.isMock()` 能力位与 `QaServiceImpl` 中依赖它的分支。
- 删除 `RAG_CHAT_PROVIDER`、`RAG_EXTRACT_PROVIDER`、`RAG_DRAFT_PROVIDER` 三个开关。
- 对话生成、抽取生成、草稿生成三处接口从 `strategy` 布局改为普通 `service` + `service/impl` 布局，单实现不再需要条件装配。
- 向量/嵌入、文档解析、切片仍保留策略模式：它们有多个实现且切换是真实需求（`hash`/`zhipu`、`docx`/`txt`、按章节/固定）。

**二、业务样例工具保留，按数据来源如实命名**

- 保留 `customer.profile` / `lender.product` 两个计划工具，类名从 `Mock*` 改为 `SampleCustomerProfileTool` / `SampleLenderProductTool`，数据目录改为 `resources/sample/`。
- 开关从 `rag.plan.tools.mock-enabled` 更名为 `rag.plan.tools.sample-enabled`（环境变量 `RAG_PLAN_TOOLS_SAMPLE_ENABLED`），默认仍为 `false`；白名单取值 `customer.profile` / `lender.product` 不变。
- 命名原则：它们是**本地固定样例数据源**，不是「假的模型」。凡展示或文档提及必须标明数据来源，避免假数据被当成真实业务数据。

**三、`ToolIntentRouter` 删除**

它的唯一作用是在没有真实模型时按关键词猜「要不要调业务工具」。删除 `MockAnswerGeneratorStrategy` 后，`src/main` 与 `src/test` 中已无任何引用，属死代码，一并删除。

**四、无 key 场景改为启动失败**

- `LLM_API_KEY` 缺失 → 启动直接失败，由 Spring AI 装配 `OpenAiApi` 时抛 `OpenAI API key must be set`（点名 `spring.ai.openai.api-key`），不额外维护自定义校验类。
- `ZHIPU_API_KEY` 仅在 `rag.embedding.provider=zhipu` 时必需，由 `ZhipuEmbeddingStrategy` 装配期校验；框架对该自定义 HTTP 客户端无校验。
- 有默认值的配置项缺失时不阻塞启动。

## 备选方案与否决理由

- **保留模型 mock 但默认关闭**：仍要维护两套实现与 `isMock()` 分支，只是把维护成本推给未来。
- **模型 mock 下沉到测试**：测试已能用 Testcontainers 与桩服务覆盖，运行期 mock 不是测试手段。
- **连样例业务工具一起删**：混淆了「模型替身」与「本地业务数据源」，会让计划执行链路失去验证业务工具的样本。
- **一并删除向量 hash 实现**：hash 是当前默认向量源，也是关键词分词的复用点，删除会让无外部依赖的本地开发不可用。

## 后果

- 本地/CI 必须提供 `LLM_API_KEY` 才能启动服务；集成测试通过 `@SpringBootTest(properties=...)` 提供占位 key，不调模型。
- `llm_call_log.provider` 恒为真实模型名，前端 `done.provider` 字段契约不变。
- 样例工具默认关闭，生产环境计划白名单只有 `kb.search`；演示/联调需显式打开 `RAG_PLAN_TOOLS_SAMPLE_ENABLED`，且产物标注为本地样例数据。
- 默认档单测 277 → 256（删除模型 mock 相关用例，新增启动配置校验 2 例，恢复样例工具 4 例与 `knownDocIds` 1 例）。
