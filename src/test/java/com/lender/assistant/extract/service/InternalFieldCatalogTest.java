package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.InternalField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内部标准字段集的守卫测试：12 个字段是抽取准确率的"对照物"，
 * id 一旦变动，field_mapping 表历史数据、评估 golden、前端展示全部受牵连——
 * 所以这里锁死数量、唯一性、字段完整性三个不变量。
 */
class InternalFieldCatalogTest {

    @Test
    @DisplayName("字段集恰好 12 个，id 唯一，所有字段信息完整")
    void twelveFieldsWithUniqueIds() {
        assertEquals(12, InternalFieldCatalog.ALL.size(), "字段集规模变更必须显式改本测试——这是评估口径的一部分");

        Set<String> ids = new HashSet<>();
        for (InternalField f : InternalFieldCatalog.ALL) {
            assertFalse(ids.contains(f.id()), "字段 id 重复: " + f.id());
            ids.add(f.id());
            assertFalse(f.name() == null || f.name().isBlank(), f.id() + " 缺名称");
            assertFalse(f.description() == null || f.description().isBlank(), f.id() + " 缺描述");
        }
    }

    @Test
    @DisplayName("计划第二节的 12 个字段 id 全部在目录中")
    void expectedFieldIdsPresent() {
        String[] expected = {
                "lender_name", "gateway_url", "uat_url", "auth_method", "credentials",
                "sign_rule", "payment_api", "collection_api", "query_api",
                "status_enum", "file_delivery", "file_format"
        };
        for (String id : expected) {
            assertNotNull(InternalFieldCatalog.byId(id), "缺少计划约定的字段: " + id);
        }
        assertNull(InternalFieldCatalog.byId("no_such_field"), "未知 id 应返回 null 而非抛异常");
    }
}
