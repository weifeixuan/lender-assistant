# ADR-0004：数据访问统一 MyBatis 及其映射约定

- 状态：已采纳
- 相关：`docs/agents/coding-standards.md §6`、`rag/mapper/DocChunkMapper`、`extract/mapper/FieldMappingMapper`、`resilience/mapper/LlmCallLogMapper`、`docs/schema.sql`

## 背景

早期数据访问是在 `Store` 类里用 `JdbcTemplate` 直写 SQL。此次统一迁移到 MyBatis（Mapper 接口 + XML）。迁移期间在真实库冒烟中暴露过 record 结果映射的问题：MyBatis 会把 `javaType` 别名 `int` / `long` / `double` 解析为包装类，映射时向构造函数传入 boxed 值，record 若用 primitive 组件会抛 `NoSuchMethodException`。此外 pgvector 的专用语法若下沉到 service 会污染业务层。

## 决策

- 数据访问**统一 MyBatis**；SQL 只存在于 `mapper/` 层，载体由 `Store` + JdbcTemplate 改为 Mapper 接口 + `resources/mapper/*.xml`。
- 参数占位一律 `#{}`，**严禁 `${}`**。
- 结果映射返回 PO / DTO 并显式 `<resultMap>`；**禁止返回 `Map<String,Object>`**。
- **`<constructor>` 映射的 record，数值组件必须用包装类型（`Integer` / `Long` / `Double`）**；XML 的 `javaType` 写显式包装类名（如 `java.lang.Long`）；可空数值列用 `COALESCE(col, 0)` 兜底避免拆箱 NPE。
- **pgvector 专用语法只允许出现在 Mapper XML**（`CAST(#{vec} AS vector)`、`<=>`、`SIMILARITY`），不得泄漏到 service。
- **分页手写** `LIMIT #{limit} OFFSET #{offset}` + 另查 `COUNT(*)`，不引分页插件。
- 幂等写入用 upsert（`ON CONFLICT ... DO UPDATE`）；先删后插须注释说明为何不用 upsert。

## 理由

1. XML 直写 SQL 能完整表达 pgvector 语法与自定义排序，不需要为 ORM 的抽象付出绕路成本；也不用为了迁就 ORM 放弃手写 SQL 的可控性。
2. `Map<String,Object>` 作契约会丢失字段类型与文档性，record / DTO + 显式 `<resultMap>` 让映射错误在编译期或启动期就暴露。
3. 包装类型是 MyBatis 反射构造的硬约束：`javaType` 别名解析 + boxed 传参 + primitive 形参三者不匹配即 `NoSuchMethodException`，用包装类型一次性对齐。
4. 分页需求简单（单表、少量用户），引插件带来的隐式 SQL 改写不值当。

## 后果

- 全 Mock 单测测不到 Mapper XML ↔ 接口绑定、`<resultMap>` 列序与 starter 启动兼容性；这类问题只能在真实库冒烟中暴露（见 ADR-0006 与 coding-standards「遗留待办」）。
- 迁移要求「一处一次迁完」，不留「半个方法 JdbcTemplate、半个 Mapper」；迁移后既有测试基线（如 `SearchServiceTest`）必须仍通过。
