package com.lender.assistant.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PgVectorUtils 单元测试：float[] → pgvector 文本字面量的唯一落点。
 * 字面量格式一旦漂移，MyBatis 侧 CAST(#{vec} AS vector) 会直接报解析失败，故锁死。
 */
class PgVectorUtilsTest {

    @Test
    @DisplayName("toLiteral：普通向量按 [a,b] 形式拼接，float 文本不丢小数")
    void toLiteralJoinsComponents() {
        // Float.toString(1.0f)="1.0"、2.0f="2.0"，故预期字面量必须带 .0
        String literal = PgVectorUtils.toLiteral(new float[]{1.0f, 2.0f});

        assertEquals("[1.0,2.0]", literal, "float 分量须为 1.0/2.0，不能退化成 1 或空");
    }

    @Test
    @DisplayName("toLiteral：空数组返回空向量字面量 []")
    void toLiteralHandlesEmptyArray() {
        String literal = PgVectorUtils.toLiteral(new float[0]);

        assertEquals("[]", literal, "空数组应产出 []，不能是 [ 或空串");
    }
}
