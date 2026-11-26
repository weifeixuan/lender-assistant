# 01: 会话摘要数据基础

**What to build:** 会话能把滚动摘要及其覆盖边界存进库、读出来：`conversation` 表新增 `summary` 与 `summary_boundary_round_id` 两列（幂等迁移 + 列注释），实体与 Mapper 同步；写回走**独立 UPDATE**——只更两列、不 touch `update_time`（时间线不被后台任务推后）、带 `status='ACTIVE'` 条件（已删会话命中 0 行，与 touch 先例同构）。本票只交付数据能力，不接业务流程。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `docs/schema.sql`：conversation 两列 DDL + `ADD COLUMN IF NOT EXISTS` 幂等迁移 + 两条 `COMMENT ON COLUMN`（边界列注明「与 summary 同写同空」）
- [x] `SchemaContractTest` 断言点：两列存在与类型、两条幂等迁移语句、两条 COMMENT（先 RED 后 GREEN）
- [x] 实体加字段，Mapper 接口与 XML resultMap 同步；Mapper XML 契约测试绿
- [x] `updateSummary` 独立 UPDATE：只更 summary 两列，`WHERE conversation_id = #{...} AND status = 'ACTIVE'`，不更新 `update_time`
- [x] `mvn test` 全绿
