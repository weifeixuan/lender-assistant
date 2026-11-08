package com.lender.assistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema 契约测试：只读取 docs/schema.sql 的文本，不连数据库。
 *
 * 它证明的是「建表事实源里写下了这些结构与约束」，不证明 PostgreSQL 会实际拒绝重复写入——
 * 后者由票 11（Testcontainers）与票 01 的 psql 手工步骤覆盖（ADR-0006）。
 * 断言前把连续空白折叠为单空格，避免格式对齐变化导致假红。
 */
class SchemaContractTest {

    private static final String SCHEMA = readSchema();

    private static String readSchema() {
        try {
            return Files.readString(Path.of("docs", "schema.sql")).replaceAll("\\s+", " ");
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 docs/schema.sql", e);
        }
    }

    @Test
    @DisplayName("schema 定义 conversation 表")
    void definesConversationTable() {
        assertTrue(SCHEMA.contains("CREATE TABLE IF NOT EXISTS conversation ("),
                "缺少 conversation 建表语句");
    }

    @Test
    @DisplayName("schema 定义 message 表")
    void definesMessageTable() {
        assertTrue(SCHEMA.contains("CREATE TABLE IF NOT EXISTS message ("),
                "缺少 message 建表语句");
    }

    @Test
    @DisplayName("conversation 的 conversation_id 唯一")
    void conversationIdIsUnique() {
        assertTrue(SCHEMA.contains(
                        "CONSTRAINT uk_conversation_conversation_id UNIQUE (conversation_id)"),
                "缺少 conversation_id 唯一约束");
    }

    @Test
    @DisplayName("message 的 message_id 唯一，且一轮同角色唯一")
    void messageUniquenessIsDeclared() {
        assertTrue(SCHEMA.contains("CONSTRAINT uk_message_message_id UNIQUE (message_id)"),
                "缺少 message_id 唯一约束");
        assertTrue(SCHEMA.contains(
                        "CONSTRAINT uk_message_round_role UNIQUE (conversation_id, round_id, role)"),
                "缺少 (conversation_id, round_id, role) 唯一约束");
    }

    @Test
    @DisplayName("会话列表与消息历史所需索引已声明")
    void indexesAreDeclared() {
        assertTrue(SCHEMA.contains(
                        "idx_conversation_user_status_update ON conversation (user_id, status, update_time DESC, id DESC)"),
                "会话列表索引必须覆盖排序第二键 id DESC");
        assertTrue(SCHEMA.contains("idx_message_conversation_id ON message (conversation_id, id)"),
                "缺少消息历史索引");
    }

    @Test
    @DisplayName("message 声明路由与计划快照两列且可空")
    void messageDeclaresRouteAndExecutionPlan() {
        assertTrue(SCHEMA.contains("route JSONB"), "缺少 message.route 列");
        assertTrue(SCHEMA.contains("execution_plan JSONB"), "缺少 message.execution_plan 列");
        assertTrue(SCHEMA.contains("ADD COLUMN IF NOT EXISTS route JSONB"),
                "route 必须由幂等语句补齐");
        assertTrue(SCHEMA.contains("ADD COLUMN IF NOT EXISTS execution_plan JSONB"),
                "execution_plan 必须由幂等语句补齐");
        assertTrue(SCHEMA.contains("COMMENT ON COLUMN message.route"),
                "route 缺少列注释");
        assertTrue(SCHEMA.contains("COMMENT ON COLUMN message.execution_plan"),
                "execution_plan 缺少列注释");
    }

    @Test
    @DisplayName("审计列统一为 create_time / update_time，存量库走幂等改名")
    void auditColumnsAreUnified() {
        assertTrue(SCHEMA.contains(
                        "ALTER TABLE field_mapping RENAME COLUMN created_at TO create_time"),
                "field_mapping 必须把存量 created_at 改名为 create_time");
        assertTrue(SCHEMA.contains(
                        "ALTER TABLE field_mapping ADD COLUMN IF NOT EXISTS update_time"),
                "field_mapping 必须幂等补齐 update_time");
        assertTrue(SCHEMA.contains(
                        "ALTER TABLE llm_call_log RENAME COLUMN created_at TO create_time"),
                "llm_call_log 必须把存量 created_at 改名为 create_time");
        assertTrue(SCHEMA.contains(
                        "idx_llm_call_log_create_time ON llm_call_log (create_time DESC)"),
                "调用日志索引必须按新列名重建");
    }

    @Test
    @DisplayName("llm_call_log.scene 注释列出实际取值")
    void llmSceneCommentListsActualValues() {
        assertTrue(SCHEMA.contains("COMMENT ON COLUMN llm_call_log.scene"),
                "scene 缺少列注释");
        assertTrue(SCHEMA.contains("qa / extract / draft / route / plan / synthesis"),
                "scene 注释必须与 LlmScene 的实际取值一致");
    }

    @Test
    @DisplayName("两张新表都有 COMMENT ON TABLE")
    void tablesAreDocumented() {
        assertTrue(SCHEMA.contains("COMMENT ON TABLE conversation"),
                "conversation 缺少表注释");
        assertTrue(SCHEMA.contains("COMMENT ON TABLE message"),
                "message 缺少表注释");
    }
}
