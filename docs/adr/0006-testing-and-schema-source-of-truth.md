# ADR-0006：单测 Mock 粒度与建表事实源

- 状态：已采纳
- 相关：`docs/agents/coding-standards.md §10.2 / §6.9`、`docs/schema.sql`、`resilience/LlmCallLogger`、`SearchServiceTest`

## 背景

两条来自真实事故的约束：

1. **测试假绿**：曾把 `LlmCallLogger` 整个 mock 掉，`record()` 变成空壳，编排契约实际没被测到；另有 RRF 分数断言手算算错，把正确的代码判成错误。
2. **环境不可重建**：手动 `psql` 补的 `CREATE EXTENSION vector` 没有回写 `docs/schema.sql`，导致 compose 全新库直接报 `type vector does not exist`。

## 决策

- **Mock 粒度**：单测不连真库，mock 数据访问层；**但协作对象用真实实例优于过度 mock**（如保留真实 `LlmCallLogger` + mock 其 Mapper）。
- **断言纪律**：断言值必须手算核对；一个测试只验证一个行为；bug 修复先补能复现的 RED 测试。
- **建表事实源**：新表必须有 `COMMENT ON`；**任何 schema 变更都必须同步回写 `docs/` 下的 schema SQL**，手动改库不回写视为未完成。
- **真库验证方式（修订）**：**不写** `@SpringBootTest` 真库冒烟测试。Mapper XML↔接口绑定、`<resultMap>` 列序、starter 启动兼容性改由**起服务后的手工验证**（前端页面 / curl）覆盖。此项取代本 ADR 初版的「补真库冒烟测试」决策。

## 理由

1. 把整块协作对象 mock 掉，测试就退化为「验证 mock 自己」，编排契约与副作用（留痕、降级）全部测不到——mock 的边界应停在「外部资源」而非「协作逻辑」。
2. 断言是测试的唯一输出，手算核对是防止「用错误预期锁死正确实现」的最低成本手段。
3. schema 的唯一事实源若是「某台机器的数据库实例」，环境就不可重建；`docs/schema.sql` 是可审查、可复现的事实源。
4. 真库冒烟测试的两条路都不划算：走 Testcontainers 要新增测试依赖并依赖本机 Docker 常驻；复用 docker-compose 开发库则会往开发库写数据、与外部环境耦合。而它想覆盖的盲区（Mapper 绑定、列序、starter 兼容性）在「起服务手工验证」时同样会暴露，故不单测化。

## 后果

- 全 Mock 单测存在固有盲区（Mapper XML 绑定、MVC 异常解析链、SSE 真实 flush 行为），这些问题**只在起服务后的手工验证中暴露**——不写单测，也就不会在 `mvn test` 里报警，改动 Mapper / `<resultMap>` / starter 依赖后必须真机走一遍。
- 修改 schema 的 commit 若未带上 `docs/schema.sql` 变更，视为提交不完整。
