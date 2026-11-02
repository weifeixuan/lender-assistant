package com.lender.assistant.conversation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lender.assistant.conversation.model.MessageInsert;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper XML 契约测试：用空的 MyBatis Configuration 解析 XML，不建立任何数据库连接。
 *
 * 覆盖的是 XML 语法、resultMap 类型解析与语句 ID 注册；不覆盖 SQL 语义与真实约束
 * （ADR-0006 明确不写真库冒烟测试）。
 */
class MapperXmlContractTest {

    private static final String CONVERSATION_MAPPER =
            "mapper/ConversationMapper.xml";
    private static final String MESSAGE_MAPPER =
            "mapper/MessageMapper.xml";

    @Test
    @DisplayName("ConversationMapper.xml 可解析且语句完整")
    void conversationMapperParses() {
        Configuration configuration = parse(CONVERSATION_MAPPER);
        String namespace = "com.lender.assistant.conversation.mapper.ConversationMapper";
        assertTrue(configuration.hasStatement(namespace + ".insert"),
                "缺少 insert 语句");
        assertTrue(configuration.hasStatement(namespace + ".listActiveByUser"),
                "缺少 listActiveByUser 语句");
    }

    @Test
    @DisplayName("MessageMapper.xml 可解析且语句完整")
    void messageMapperParses() {
        Configuration configuration = parse(MESSAGE_MAPPER);
        String namespace = "com.lender.assistant.conversation.mapper.MessageMapper";
        assertTrue(configuration.hasStatement(namespace + ".insert"),
                "缺少 insert 语句");
        assertTrue(configuration.hasStatement(namespace + ".listByConversation"),
                "缺少 listByConversation 语句");
    }

    @Test
    @DisplayName("MessageInsert 的 record 组件可被 MyBatis 按属性名读取")
    void messageInsertExposesProperties() {
        MessageInsert insert = new MessageInsert("conv-1", "round-1", "msg-1",
                "ASSISTANT", "回答", "DONE", "[]", "mock", 10, null);
        assertEquals("conv-1",
                SystemMetaObject.forObject(insert).getValue("conversationId"));
        assertEquals("[]", SystemMetaObject.forObject(insert).getValue("sourcesJson"));
    }

    private Configuration parse(String resource) {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
            return configuration;
        } catch (IOException e) {
            throw new IllegalStateException("无法解析 " + resource, e);
        }
    }
}
