package com.lender.assistant.extract.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FieldMappingMapper.xml 契约测试：只解析 XML 文本，不建立数据库连接。
 *
 * 覆盖确认/修正两条 UPDATE 必须刷新 update_time（阶段 8 审计列统一）；
 * SQL 语义与真实约束仍由起服务后的手工路径验证（ADR-0006）。
 */
class FieldMappingMapperContractTest {

    private static final String RESOURCE = "mapper/FieldMappingMapper.xml";
    private static final String NAMESPACE = "com.lender.assistant.extract.mapper.FieldMappingMapper";

    @Test
    @DisplayName("XML 可解析且确认/修正语句存在")
    void mapperParses() {
        Configuration configuration = parse();
        assertTrue(configuration.hasStatement(NAMESPACE + ".confirmCorrected"),
                "缺少 confirmCorrected 语句");
        assertTrue(configuration.hasStatement(NAMESPACE + ".confirmAsIs"),
                "缺少 confirmAsIs 语句");
    }

    @Test
    @DisplayName("人工确认与修正都刷新 update_time")
    void confirmStatementsRefreshUpdateTime() {
        Configuration configuration = parse();
        String corrected = configuration.getMappedStatement(NAMESPACE + ".confirmCorrected")
                .getBoundSql(Map.of("docId", "doc-1", "internalFieldId", "field-1",
                        "correctedFieldName", "", "correctedValue", ""))
                .getSql();
        assertTrue(corrected.contains("update_time"), "confirmCorrected 必须刷新 update_time");
        String asIs = configuration.getMappedStatement(NAMESPACE + ".confirmAsIs")
                .getBoundSql(Map.of("docId", "doc-1", "internalFieldId", "field-1"))
                .getSql();
        assertTrue(asIs.contains("update_time"), "confirmAsIs 必须刷新 update_time");
    }

    private Configuration parse() {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
            return configuration;
        } catch (IOException e) {
            throw new IllegalStateException("无法解析 " + RESOURCE, e);
        }
    }
}
