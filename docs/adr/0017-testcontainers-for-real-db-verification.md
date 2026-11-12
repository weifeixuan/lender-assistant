# ADR-0017：真库验证改用 Testcontainers

- 状态：已采纳（2026-11-12）
- 相关：`docs/agents/coding-standards.md §9.2 / §9.7`、`pom.xml`、`src/test/java/com/lender/assistant/testutil/PostgresContainerSupport`、`DocChunkMapperIntegrationTest`、`MessageMapperIntegrationTest`、`docs/schema.sql`、ADR-0006

## 背景

ADR-0006 在「真库验证方式（修订）」中明确否决 Testcontainers，理由有两条：

1. 要新增测试依赖，并依赖本机 Docker 常驻；
2. 复用 docker-compose 开发库则会往开发库写数据。

于是当时的结论是「不写 `@SpringBootTest` 真库冒烟测试，由起服务后手工验证覆盖」。这套做法运行一段时间后暴露出三个问题：

- **手工验证不可重复**：验证过程不进仓库，换个人、换台机器就重来一遍，也无法在提交前自动报警；
- **缺陷类型覆盖不到**：Mapper XML 的 `<resultMap>` 列序、`javaType` 与构造函数匹配、`UNIQUE` / `CHECK` 约束、`VECTOR(1024)` 维度不匹配，这些只有真库会报，mock Mapper 与 `schema.sql` 契约测试都发现不了；
- **schema 漂移缺少护栏**：`docs/schema.sql` 是建表事实源，但没有任何自动化步骤真的拿它建过库，扩展、索引、约束写错时无人察觉。

同时，当初否决的两条理由在当前环境已经不再成立，见下文。

## 决策

真库验证从「手工起服务验证」改为「Testcontainers 集成测试」：

1. **依赖**：Testcontainers 2.0.5，经 `testcontainers-bom` 导入，覆盖 Spring Boot BOM 管理的 1.21.x。2.x 的坐标已改成 `testcontainers-*` 前缀（`testcontainers`、`testcontainers-jdbc`、`testcontainers-postgresql`、`testcontainers-junit-jupiter`），Boot BOM 不再兜底，必须由本项目 BOM 显式管理，避免模块 2.x 与核心被传递依赖拉回 1.x 的混装。
2. **容器**：`pgvector/pgvector:pg16`，容器启动时把 `docs/schema.sql` 挂载为初始化脚本，保证「建库」与事实源逐字一致，不在测试里另写一份 DDL。
3. **基类**：`PostgresContainerSupport` 用静态单例容器 + `@DynamicPropertySource` 注入 `spring.datasource.*`，`@SpringBootTest` 起真实上下文；`@Testcontainers(disabledWithoutDocker = true)` 保证无 Docker 的机器上跳过而不是失败。
4. **分层开关**：集成测试类统一标 `@Tag("docker")`；surefire 默认 `excludedGroups=docker`，`mvn test` 只跑纯单测；`mvn test -Pintegration-tests` 把 `excludedGroups` 置空放开。两条命令互不干扰，默认档不需要 Docker。
5. **资源回收**：由 Ryuk（`testcontainers/ryuk:0.14.0`）负责，测试进程异常退出也不会残留容器。
6. **配套规则**：`docs/agents/coding-standards.md` §9.2 保留「单测不连真库、mock 数据访问层」，§9.7 改为本节方案；ADR-0006 状态标记为已被本 ADR 取代，文件保留不删。

## 当年否决理由为何不再成立

- **「要新增测试依赖」**：实际只增加 4 个 `test` scope 的 artifact，且由 BOM 统一管理版本，不进运行时制品、不影响启动与部署。相比它对 Mapper / schema 类缺陷的覆盖，成本可忽略。
- **「依赖本机 Docker 常驻」**：开发机与 CI 已具备 Docker；本地镜像（`pgvector/pgvector:pg16`、`testcontainers/ryuk:0.14.0`）拉取一次后落盘，后续离线可跑。无 Docker 的机器只是跳过这 9 个用例，默认档不受影响，不再是「必须常驻」的硬门槛。
- **「复用 docker-compose 开发库会写脏数据」**：这条仍然成立，因此本决策明确不复用开发库，改为一次性容器，用完即销毁。

## 被否决的替代方案

- **复用 docker-compose 开发库**：会往开发库写测试数据，且与环境变量、他人数据强耦合，无法在 CI 复现。
- **继续手工起服务验证**：不可重复、无自动报警，正是本次要修的问题。
- **H2 / 嵌入式 PostgreSQL**：没有 `pgvector`，`VECTOR(1024)`、`<=>` 余弦距离、`hnsw` 索引都无法验证，语义与生产不符。
- **mock Mapper + `schema.sql` 契约测试覆盖一切**：能测 SQL 形状，测不到真实执行计划、约束冲突与类型转换。

## 后果

- 默认档（`mvn test`）277 个用例、0 skipped、无需 Docker；
- 集成档（`mvn test -Pintegration-tests`）286 个用例、0 skipped，其中 `MessageMapperIntegrationTest` 3 个、`DocChunkMapperIntegrationTest` 6 个真实建容器、起 Spring 上下文并执行 SQL 断言；
- 首次运行需要能拉取 `pgvector/pgvector:pg16` 与 `testcontainers/ryuk:0.14.0`；之后本地有缓存即可离线运行；
- CI 若要有真库覆盖，执行器必须具备 Docker 能力；不具备时集成档整体跳过，不阻塞流水线；
- 新增 Mapper 或修改 `<resultMap>` 时，除默认档全绿外必须补一次集成档验证。
