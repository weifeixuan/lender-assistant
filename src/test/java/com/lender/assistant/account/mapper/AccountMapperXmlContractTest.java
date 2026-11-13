package com.lender.assistant.account.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 账户领域 Mapper XML 契约：只解析 XML 与参数绑定，不连库（§9.2）。
 * 语句缺失、resultMap 类型写错、XML 引用了不存在的参数前缀都会在这里红。
 */
class AccountMapperXmlContractTest {

    private static final String USER_MAPPER = "mapper/AppUserMapper.xml";
    private static final String ROLE_MAPPER = "mapper/AppRoleMapper.xml";
    private static final String USER_ROLE_MAPPER = "mapper/UserRoleMapper.xml";
    private static final String SESSION_MAPPER = "mapper/UserSessionMapper.xml";

    private static final String USER_NS = "com.lender.assistant.account.mapper.AppUserMapper";
    private static final String ROLE_NS = "com.lender.assistant.account.mapper.AppRoleMapper";
    private static final String USER_ROLE_NS = "com.lender.assistant.account.mapper.UserRoleMapper";
    private static final String SESSION_NS = "com.lender.assistant.account.mapper.UserSessionMapper";

    @Test
    @DisplayName("AppUserMapper.xml 可解析且语句完整")
    void userMapperParses() {
        Configuration configuration = parse(USER_MAPPER);
        for (String statement : new String[]{"findByUsernameIgnoreCase", "findById",
                "insert", "listRolesByUser", "listPermissionCodesByUser", "listAll",
                "updateProfile", "updatePassword", "updateStatus",
                "countEnabledAccountManagersExcluding"}) {
            assertTrue(configuration.hasStatement(USER_NS + "." + statement),
                    "缺少 " + statement + " 语句");
        }
    }

    @Test
    @DisplayName("AppRoleMapper.xml 可解析且语句完整")
    void roleMapperParses() {
        Configuration configuration = parse(ROLE_MAPPER);
        assertTrue(configuration.hasStatement(ROLE_NS + ".findByNameIgnoreCase"),
                "缺少 findByNameIgnoreCase 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".insert"), "缺少 insert 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".listByIds"), "缺少 listByIds 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".listPermissionCodesByRoles"),
                "缺少 listPermissionCodesByRoles 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".listAllWithAccountCount"),
                "角色列表需要带引用账户数");
        assertTrue(configuration.hasStatement(ROLE_NS + ".findById"), "缺少 findById 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".update"), "缺少 update 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".deleteById"), "缺少 deleteById 语句");
        assertTrue(configuration.hasStatement(ROLE_NS + ".countAccountsByRole"),
                "删除前需要引用计数");
        assertTrue(configuration.hasStatement(ROLE_NS + ".listPermissionCodes"),
                "角色详情需要单角色权限码");
        assertTrue(configuration.hasStatement(ROLE_NS + ".deletePermissions"),
                "编辑角色需要整体替换权限点");
        assertTrue(configuration.hasStatement(ROLE_NS + ".insertPermission"),
                "缺少 insertPermission 语句");
    }

    @Test
    @DisplayName("UserRoleMapper.xml 可解析且按冲突忽略绑定")
    void userRoleMapperParses() {
        Configuration configuration = parse(USER_ROLE_MAPPER);
        String statementId = USER_ROLE_NS + ".insertIfAbsent";
        assertTrue(configuration.hasStatement(statementId), "缺少 insertIfAbsent 语句");
        String sql = configuration.getMappedStatement(statementId)
                .getBoundSql(new Object[]{"u1", "r1"}).getSql();
        assertTrue(sql.contains("ON CONFLICT"), "重复绑定必须幂等，用 ON CONFLICT 忽略");
        assertTrue(configuration.hasStatement(USER_ROLE_NS + ".deleteByUser"),
                "改角色集合需要先清空旧绑定");
    }

    @Test
    @DisplayName("UserSessionMapper.xml 可解析且会话查询按过期时间过滤")
    void sessionMapperParses() {
        Configuration configuration = parse(SESSION_MAPPER);
        String statementId = SESSION_NS + ".findByTokenHash";
        assertTrue(configuration.hasStatement(statementId), "缺少 findByTokenHash 语句");
        String sql = configuration.getMappedStatement(statementId)
                .getBoundSql("hash").getSql();
        assertTrue(sql.contains("expire_time > now()"), "过期判定必须交给数据库，避免时区换算偏差");
        assertTrue(configuration.hasStatement(SESSION_NS + ".deleteByUser"),
                "重置口令/禁用账户需失效该账户全部会话");
        assertTrue(configuration.hasStatement(SESSION_NS + ".deleteByUserExceptToken"),
                "改自己的口令需保留当前令牌");
    }

    @Test
    @DisplayName("用户插入参数按运行时包装规则可绑定")
    void userInsertParametersBind() throws Exception {
        Method method = AppUserMapper.class.getMethod("insert", String.class, String.class,
                String.class, String.class, String.class);
        assertParametersBind(USER_MAPPER, USER_NS + ".insert", method,
                "u1", "admin", "管理员", "$2a$10$hash", "ENABLED");
    }

    @Test
    @DisplayName("会话按摘要查询结果可映射为实体")
    void sessionResultMapResolvesTypes() {
        Configuration configuration = parse(SESSION_MAPPER);
        MappedStatement statement = configuration.getMappedStatement(SESSION_NS + ".findByTokenHash");
        assertTrue(statement.getResultMaps().get(0).getType().getName()
                        .endsWith("UserSessionEntity"),
                "resultMap 必须指向 UserSessionEntity");
    }

    private void assertParametersBind(String resource, String statementId,
                                      Method method, Object... args) throws Exception {
        Configuration configuration = parse(resource);
        MappedStatement statement = configuration.getMappedStatement(statementId);
        Object parameterObject = new ParamNameResolver(configuration, method).getNamedParams(args);
        BoundSql boundSql = statement.getBoundSql(parameterObject);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, parameterObject, boundSql)
                .setParameters(preparedStatement);
        verify(preparedStatement, atLeastOnce()).setString(anyInt(), org.mockito.ArgumentMatchers.anyString());
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
