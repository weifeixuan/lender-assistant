# 阶段 5 实施计划：代码草稿生成 + Docker Compose + README + GitHub 开源

> 规格（手册 + 三项已确认决策）：
> ① 基于映射表 + Handler 模板（策略模式骨架）生成 Java 适配层代码草稿 → API + UI 展示 + 人工复核提示
> ② Docker Compose 一键起（app + lender-pg）；③ README（架构图/快速开始/设计决策）；
> ④ GitHub 建仓：**命令备好由用户自己推**（账号认证在用户手里）；验收：克隆 → 起服务 → 提问全流程。
>
> 沿用既定原则：Mock 先行 + DeepSeek 双实现；韧性/留痕统一走 ResilientLlmExecutor；TDD 红绿推进。

## 任务 A：Handler 模板骨架（素材，task#27）

- `src/main/resources/draft/LenderHandler.java`——虚构策略模式接口：
  `LenderHandler`（`pay(PayRequest)` / `queryStatus(queryNo)` / `parseEodFile(Path)` 三能力 + `lenderCode()`）
- `src/main/resources/draft/ExampleLenderHandler.java`——示例实现，**值全部来自 12 字段映射表占位符**：
  `{{GATEWAY_URL}}` `{{AUTH_HEADER}}` `{{PAY_PATH}}` `{{QUERY_PATH}}` `{{STATUS_MAP}}` `{{EOD_DELIVERY}}`…
  占位符与 InternalFieldCatalog 字段一一对应（渲染契约）
- 骨架不进 `src/main/java`（不参与编译，纯素材），包名用 `com.lender.draft.generated`
- 验证：骨架完整性单测（占位符集合 = 字段目录的可渲染子集，防字段加了模板忘了加）

## 任务 B：Mock 草稿生成器（task#28 RED→GREEN）

- RED `draft/MockDraftGeneratorTest`：
  - `renderFillsAllPlaceholders`：给定 ruijin 9 条映射 → 产物不含任何 `{{...}}` 残留
  - `renderValuesComeFromMappings`：`https://fake-ruijin.example.com/openapi` 等映射值出现在产物对应位置
  - `missingFieldLeftAsTodo`：映射表缺的字段 → 占位符渲染成 `// TODO 人工补充: xxx` 而非报错（宁缺勿编）
- GREEN `extract/draft/`：`DraftGenerator` 接口 + `MockDraftGenerator`（读 classpath 骨架 → 逐占位符替换）
  - `rag.draft.provider` 配置（mock 默认 / deepseek）

## 任务 C：DeepSeek 草稿生成器 + 编排（task#28）

- `DeepSeekDraftGenerator`：system=角色与铁律（只输出 Java 代码/标注 TODO/不编造映射外字段），
  user=骨架 + 映射表 JSON；key 缺失不装配（`@ConditionalOnProperty`）
- `DraftService`：加载映射表（`FieldMappingStore.listMappings`，无映射时引导先抽取）→
  `ResilientLlmExecutor.execute("draft", provider, call, allowRetry=true)` 包住生成 → 留痕
- REST `POST /api/draft/{docId}` → `{docId, draft, reviewNotice, mappingsUsed}`；
  复核声明常量（"代码草稿，必须人工复核，禁止直接上生产"）

## 任务 D：UI 第⑥区块（task#28）

- `index.html`：文档选择 → 生成草稿 → 代码块展示（等宽+换行保留）+ 复制按钮 + 顶部醒目复核声明条

## 任务 E：Dockerfile + compose（task#29）

- `Dockerfile`：多阶段（maven:3.9-eclipse-temurin-21 构建 → eclipse-temurin:21-jre 运行）
- `docker-compose.yml`：`lender-pg`（pgvector/pgvector:pg16 + schema SQL 挂 `/docker-entrypoint-initdb.d/` + healthcheck）
  + `app`（depends_on condition: service_healthy，`SERVER_PORT` 等环境变量对齐 application.yml）
- 验证：`docker compose down -v && docker compose up -d` → /health UP → QA 提问通

## 任务 F：README（task#30）

- 项目一句话定位、架构文本图（上传→切分→向量化→检索→RRF→生成/抽取/草稿 的链路+韧性装饰）
- 快速开始两路（compose 一键 / 手动起容器+jar）；配置表（provider 开关、env 覆盖）
- 设计决策表（RRF、Mock先行、编程式熔断、SSE禁重试、宁缺勿编…链接 docs/ 各完成记录）
- 红线声明：全脱敏虚构资方、时间真实

## 任务 G：GitHub 建仓 + 全流程验收 + 完成文档（task#31）

- 给用户备好：`gh repo create` / `git remote add origin` + push 命令组（含 .gitignore 核查：.env 不入库）
- 全流程验收：clone 到临时目录 → compose up → 提问 → 抽取 → 草稿
- `docs/阶段5_完成记录.md`：验收记录 + 开源检查清单
