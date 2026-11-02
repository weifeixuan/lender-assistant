# 01: 会话与消息持久化骨架

**What to build:** 多轮对话所需的两张表与仓储读写落地，使"会话归属 + 轮次关联 + 消息状态"在数据库层可被验证。完成后能写入一个会话和一轮用户/助手消息，并分别按用户、按会话读出来；同一轮同一角色重复写入会被数据库拒绝。

**涉及层次:** schema 事实源、conversation/message 的 mapper 与 model、仓储集成测试；不涉及 HTTP 层。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

**Estimate:** 1–1.5 小时

- [ ] schema 事实源以幂等方式追加 conversation 与 message 两表、索引与 COMMENT；同一脚本连续执行两次不报错
- [ ] `docs/schema.sql` 契约测试断言下列文本存在：
  - `CREATE TABLE IF NOT EXISTS conversation` / `message`
  - `CONSTRAINT uk_conversation_conversation_id UNIQUE (conversation_id)`
  - `CONSTRAINT uk_message_message_id UNIQUE (message_id)`
  - `CONSTRAINT uk_message_round_role UNIQUE (conversation_id, round_id, role)`
  - 索引 `idx_conversation_user_status_update` 与 `idx_message_conversation_id`
  - `COMMENT ON TABLE conversation` / `COMMENT ON TABLE message`
- [ ] MyBatis mapper XML 可在**不连库**的情况下解析成功，且 `insert` / `listActiveByUser` / `listByConversation` 语句 ID 存在
- [ ] **已知缺口（显式记录）**：契约测试只证明 SQL 文本包含约束，**不证明数据库会实际拒绝重复写入**；真库拒绝行为由票 11（Testcontainers）覆盖
- [ ] 起服务后用 psql 手工验证真实唯一约束、索引排序与 JSONB 往返，步骤见下方「手工验证」
- [ ] `mvn test` 全绿

## 手工验证（真实 PostgreSQL）

Docker 可用后按以下步骤执行；全部包在事务里并回滚，不污染开发库。

```bash
# 1. schema 幂等：连续执行两次，均不应报错
docker exec -i lender-pg psql -U postgres -d lender_assistant < docs/schema.sql
docker exec -i lender-pg psql -U postgres -d lender_assistant < docs/schema.sql

# 2. 唯一约束：同轮第二条 ASSISTANT 应被拒绝（期望 duplicate key / uk_message_round_role）
docker exec -i lender-pg psql -U postgres -d lender_assistant <<'SQL'
BEGIN;
INSERT INTO conversation (conversation_id, user_id, title)
VALUES ('11111111-1111-1111-1111-111111111111', 'dev-user-001', '手工验证会话');
INSERT INTO message (conversation_id, round_id, message_id, role, content, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'round-1', 'msg-user-1', 'USER', '问题', 'DONE');
INSERT INTO message (conversation_id, round_id, message_id, role, content, status, sources)
VALUES ('11111111-1111-1111-1111-111111111111', 'round-1', 'msg-assistant-1', 'ASSISTANT', '回答', 'DONE', '[]'::jsonb);
-- 下面这条必须失败：violates unique constraint "uk_message_round_role"
INSERT INTO message (conversation_id, round_id, message_id, role, content, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'round-1', 'msg-assistant-2', 'ASSISTANT', '第二个回答', 'DONE');
ROLLBACK;
SQL

# 3. 排序：同用户两条会话，update_time 较新的应排在前面
docker exec -i lender-pg psql -U postgres -d lender_assistant <<'SQL'
BEGIN;
INSERT INTO conversation (conversation_id, user_id, title, update_time)
VALUES ('22222222-2222-2222-2222-222222222222', 'dev-user-001', '旧会话', now() - interval '1 hour');
INSERT INTO conversation (conversation_id, user_id, title, update_time)
VALUES ('33333333-3333-3333-3333-333333333333', 'dev-user-001', '新会话', now());
SELECT conversation_id, title FROM conversation
WHERE user_id = 'dev-user-001' AND status = 'ACTIVE'
ORDER BY update_time DESC, id DESC;
-- 期望：新会话在前
ROLLBACK;
SQL

# 4. JSONB 往返：助手消息 sources 读出后仍是数组结构
docker exec -i lender-pg psql -U postgres -d lender_assistant <<'SQL'
BEGIN;
INSERT INTO conversation (conversation_id, user_id, title)
VALUES ('44444444-4444-4444-4444-444444444444', 'dev-user-001', 'JSONB 验证');
INSERT INTO message (conversation_id, round_id, message_id, role, content, status, sources)
VALUES ('44444444-4444-4444-4444-444444444444', 'round-json', 'msg-json', 'ASSISTANT', '回答', 'DONE',
        '[{"idx":1,"docTitle":"资料"}]'::jsonb);
SELECT sources::text FROM message WHERE message_id = 'msg-json';
-- 期望：[{"idx": 1, "docTitle": "资料"}]
ROLLBACK;
SQL
```

说明：票 01 阶段还没有会话接口，故此处不列 curl；接口出现后由票 02/03 各自的验收覆盖 HTTP 层。
