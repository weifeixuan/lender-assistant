package com.lender.assistant.conversation;

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
                        "idx_conversation_user_status_update ON conversation (user_id, status, update_time DESC)"),
                "缺少会话列表索引");
        assertTrue(SCHEMA.contains("idx_message_conversation_id ON message (conversation_id, id)"),
                "缺少消息历史索引");
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
