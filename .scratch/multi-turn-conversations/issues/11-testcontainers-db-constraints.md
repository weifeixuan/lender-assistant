# 11: 用 Testcontainers 补真库约束验证

**What to build:** 用一个一次性 PostgreSQL 容器加载 `docs/schema.sql`，在真实数据库上验证票 01 无法证明的约束行为：同一轮写入第二条 `ASSISTANT`（或第二条 `USER`）被拒绝、列表排序稳定、`sources` JSONB 可往返。测试结束容器销毁，不接触开发库。

**涉及层次:** 测试作用域依赖（Testcontainers + PostgreSQL 模块）、数据库集成测试、schema 加载工具。

**Blocked by:** 01

**Status:** ready-for-agent

**Estimate:** 1.5–2 小时

**前置:** Docker 可用（当前环境已满足）。本票暂不落地依赖，仅登记。

- [ ] 测试作用域新增 `testcontainers` 与 `testcontainers-postgresql`（版本由 Testcontainers BOM 或 Spring Boot 依赖管理统一）
- [ ] 集成测试启动 `postgres:16` 容器并执行 `docs/schema.sql`，执行两次不报错
- [ ] 同一 `(conversation_id, round_id, role)` 写入第二条 `ASSISTANT` 时抛出 SQL 异常，且可断言约束名为 `uk_message_round_role`
- [ ] 同一轮写入第二条 `USER` 同样被拒绝
- [ ] 会话列表按 `update_time DESC, id DESC` 返回；消息按 `id ASC` 返回
- [ ] `sources` 写为 `[]` 与写为带元素数组后，读回结构一致；用户消息 `sources` 为 `NULL`
- [ ] Docker 不可用时测试**跳过而非失败**（例如 `disabledWithoutDocker = true`）
- [ ] `mvn test` 在 Docker 可用时全绿，且测试不连接开发库（使用随机端口容器）
