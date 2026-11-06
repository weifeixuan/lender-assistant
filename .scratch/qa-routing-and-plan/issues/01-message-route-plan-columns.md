# 01: message 表新增 route / execution_plan 列

**阶段:** P1 路由
**Blocked by:** None (can start immediately)
**Status:** ready-for-agent

**范围:** `docs/schema.sql`（唯一事实源）+ 消息持久化层读写。只做存储，不做业务判定。

**要做的事:** 让每轮助手消息可以把路由结果与计划执行快照落库并可读回；空库执行 schema 后可直接启动。

**涉及文件/类:** `docs/schema.sql`、消息行模型与 mapper、消息仓储。

- [ ] `message` 表新增两个可空 JSONB 列：`route`、`execution_plan`；使用幂等语法，脚本连续执行两次不报错
- [ ] 两列均补 `COMMENT ON COLUMN`
- [ ] `docs/schema.sql` 契约测试断言两列名与可空性相关文本存在
- [ ] 助手消息可写入并读回 `route`（`route` / `confidence` / `reason` / `irrelevantCategory`）与 `execution_plan`（含 `steps[]`）的 JSONB 往返，内容不做字段裁剪
- [ ] 现有历史消息接口响应形状不变，新列不外露
- [ ] `mvn test` 全绿，单测不连真库

**不做什么:** 不改 `MessageResponse`；不新增表；不建独立迁移目录；不写路由/计划业务逻辑。
