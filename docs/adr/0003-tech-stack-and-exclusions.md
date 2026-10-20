# ADR-0003：技术栈选型与不引清单

- 状态：已采纳
- 相关：`pom.xml`、`docs/agents/coding-standards.md §0.1`、`common/web/ControllerLogAspect`、`docs/adr/0001-unified-response-envelope.md`

## 背景

JVM 生态里 JPA / Lombok / MyBatis-Plus / WebFlux 常被当作默认选型。本项目有若干具体约束：向量检索需要 pgvector 专用语法（`<=>`、`SIMILARITY`、`CAST(... AS vector)`）；模型调用需要「Mock / 真实」可切换且失败可留痕；并发量小、无响应式需求；跨层数据载体统一用 Java 21 的 `record`。

## 决策

- **不引** JPA / Hibernate / MyBatis-Plus、Lombok、WebFlux、Tika。
- 数据访问**统一 MyBatis**（见 ADR-0004）。
- 韧性用 **Resilience4j 纯库**、编程式 `decorateSupplier`，不用注解 AOP。
- 引入 `spring-boot-starter-aop` **仅为** `common/web/ControllerLogAspect` 打印 controller 进出参与耗时——AOP 只切 HTTP 边界。
- 版本以 `pom.xml` 为唯一事实源，规范文档不复制版本号。

## 理由

1. pgvector 的距离排序 / 相似度 / 向量转换语法在 ORM 里表达别扭，MyBatis XML 直写 SQL 最直接、最可控。
2. `record` 已消除大部分样板代码，Lombok 的价值不足以再引一个编译期注解处理器。
3. Servlet 栈 + `SseEmitter` 足以支撑单条问答的流式输出，WebFlux 的响应式复杂度是纯负担。
4. 「韧性用注解 AOP」与「响应体出站改写」容易被混为一谈（见 ADR-0001）；把 AOP 的用途收窄到日志，切口清晰、边界不重叠。
5. 当前无 PDF 场景，Tika 属于「暂时用不上的重依赖」。

## 后果

- 新增任何第三方依赖都必须先评估「能不加就不加」，并在本 ADR 或 `coding-standards.md §0.1` 登记「选它 / 不选它」的理由。
- AOP **不得**用于响应体改写或异常处理，这两件事分别是 `ApiResponseBodyAdvice` 与 `GlobalExceptionHandler` 的职责。
