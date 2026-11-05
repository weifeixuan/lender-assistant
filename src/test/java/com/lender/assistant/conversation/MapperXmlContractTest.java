package com.lender.assistant.conversation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.mapper.MessageMapper;
import com.lender.assistant.conversation.model.MessageInsert;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Mapper XML 契约测试：用空的 MyBatis Configuration 解析 XML，不建立任何数据库连接。
 *
 * 覆盖的是 XML 语法、resultMap 类型解析、语句 ID 注册与参数绑定路径；不覆盖 SQL 语义
 * 与真实约束（ADR-0006 明确不写真库冒烟测试）。
 *
 * 参数绑定用 {@link ParamNameResolver} 复现 MapperMethod 的包装规则（单参数无 {@code @Param}
 * 时不包 ParamMap），再交给 {@link DefaultParameterHandler} 在 mock 的 PreparedStatement
 * 上取值——XML 写 {@code #{message.xxx}} 而方法参数没有 {@code @Param("message")} 时这里会红。
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
    @DisplayName("touch 只更新 ACTIVE 会话：已删会话的时间线不被推后")
    void touchRequiresActiveStatus() {
        Configuration configuration = parse(CONVERSATION_MAPPER);
        String statementId = "com.lender.assistant.conversation.mapper.ConversationMapper.touch";
        String sql = configuration.getMappedStatement(statementId).getBoundSql("conv-1").getSql();
        assertTrue(sql.contains("status = 'ACTIVE'"), "touch 必须自带 ACTIVE 条件");
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
        assertTrue(configuration.hasStatement(namespace + ".updateRouteAndPlan"),
                "缺少 updateRouteAndPlan 语句");
    }

    @Test
    @DisplayName("updateRouteAndPlan 写入两列 JSONB，读取投影暴露两列")
    void routeAndPlanColumnsAreWrittenAndRead() {
        Configuration configuration = parse(MESSAGE_MAPPER);
        String statementId = "com.lender.assistant.conversation.mapper.MessageMapper.updateRouteAndPlan";
        String sql = configuration.getMappedStatement(statementId)
                .getBoundSql(new Object[]{null, null, null, null}).getSql();
        assertTrue(sql.contains("route = CAST("), "必须写入 route");
        assertTrue(sql.contains("execution_plan = CAST("), "必须写入 execution_plan");
        String listSql = configuration
                .getMappedStatement("com.lender.assistant.conversation.mapper.MessageMapper.listByConversation")
                .getBoundSql("conv-1").getSql();
        assertTrue(listSql.contains("route::text"), "读取必须投影 route");
        assertTrue(listSql.contains("execution_plan::text"), "读取必须投影 execution_plan");
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

    @Test
    @DisplayName("MessageMapper.insert 的参数按运行时包装规则可绑定")
    void messageInsertParametersBind() throws Exception {
        Method method = MessageMapper.class.getMethod("insert", MessageInsert.class);
        MessageInsert insert = new MessageInsert("conv-1", "round-1", "msg-1",
                "ASSISTANT", "回答", "DONE", "[]", "mock", 10, null);
        assertParametersBind(MESSAGE_MAPPER,
                "com.lender.assistant.conversation.mapper.MessageMapper.insert",
                method, insert);
    }

    @Test
    @DisplayName("ConversationMapper.insert 的参数按运行时包装规则可绑定")
    void conversationInsertParametersBind() throws Exception {
        Method method = ConversationMapper.class.getMethod("insert",
                String.class, String.class, String.class);
        assertParametersBind(CONVERSATION_MAPPER,
                "com.lender.assistant.conversation.mapper.ConversationMapper.insert",
                method, "conv-1", "user-a", "首问标题");
    }

    /**
     * 按 MapperMethod 的规则从实参构造参数对象，交给 DefaultParameterHandler 逐个取值。
     * 取值失败（如 XML 引用了不存在的 {@code message} 前缀）会抛 ReflectionException。
     */
    private void assertParametersBind(String resource, String statementId,
                                      Method method, Object... args) throws Exception {
        Configuration configuration = parse(resource);
        MappedStatement statement = configuration.getMappedStatement(statementId);
        Object parameterObject = new ParamNameResolver(configuration, method)
                .getNamedParams(args);
        BoundSql boundSql = statement.getBoundSql(parameterObject);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameterObject, boundSql)
                .setParameters(preparedStatement);
        verify(preparedStatement, atLeastOnce()).setString(anyInt(), anyString());
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
