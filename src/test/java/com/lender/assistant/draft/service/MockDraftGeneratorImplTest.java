package com.lender.assistant.draft.service;

import com.lender.assistant.draft.service.impl.MockDraftGeneratorImpl;
import com.lender.assistant.extract.model.FieldMapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock 草稿生成器测试：模板渲染的确定性契约。
 *
 * 渲染契约（骨架 resources/draft/ExampleLenderHandler.java 与本测试共同锁定）：
 * - 映射表有的字段 → 值填进对应占位符，产物不含任何 {{...}} 残留
 * - 映射表缺的字段 → 占位符渲染成 "// TODO 人工补充"，宁缺勿编
 * - status_enum 是唯一需要代码整形的字段（值 → STATUS_MAP.put 行）
 */
class MockDraftGeneratorImplTest {

    private final MockDraftGeneratorImpl generator = new MockDraftGeneratorImpl();

    /** ruijin 真实抽取结果的字段值（来自阶段4验收运行） */
    private List<FieldMapping> ruijinMappings() {
        return List.of(
                new FieldMapping("lender_name", "资方名称", "资方", "瑞金租赁（虚构）", "瑞金租赁（虚构）", 0.9),
                new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                        "https://fake-ruijin.example.com/openapi", "网关地址 https://fake-ruijin.example.com/openapi", 0.9),
                new FieldMapping("payment_api", "放款指令接口路径", "放款指令接口",
                        "/openapi/loan/order/create", "接口路径 /openapi/loan/order/create", 0.9),
                new FieldMapping("status_enum", "交易状态枚举", "交易状态",
                        "SUCCESS / FAILED / PROCESSING", "交易状态 SUCCESS / FAILED / PROCESSING", 0.7));
    }

    @Test
    @DisplayName("渲染产物不含任何 {{...}} 占位符残留（缺失字段渲染成 TODO 注释）")
    void renderFillsAllPlaceholders() {
        String draft = generator.generateDraft("ruijin_api_v1", ruijinMappings());

        assertFalse(draft.contains("{{"), "残留占位符: " + firstPlaceholder(draft));
        assertTrue(draft.contains("TODO 人工补充"), "映射表只有4条，必须有缺失字段渲染为 TODO");
    }

    @Test
    @DisplayName("映射值出现在产物对应位置：网关URL、放款路径、资方名")
    void renderValuesComeFromMappings() {
        String draft = generator.generateDraft("ruijin_api_v1", ruijinMappings());

        assertTrue(draft.contains("https://fake-ruijin.example.com/openapi"));
        assertTrue(draft.contains("/openapi/loan/order/create"));
        assertTrue(draft.contains("瑞金租赁（虚构）"));
    }

    @Test
    @DisplayName("status_enum 需代码整形：值拆成 STATUS_MAP.put 行")
    void statusEnumShapedIntoMapEntries() {
        String draft = generator.generateDraft("ruijin_api_v1", ruijinMappings());

        assertTrue(draft.contains("STATUS_MAP.put(\"SUCCESS\""), "枚举值要拆成 put 行");
        assertTrue(draft.contains("STATUS_MAP.put(\"FAILED\""));
        assertTrue(draft.contains("STATUS_MAP.put(\"PROCESSING\""));
    }

    @Test
    @DisplayName("空映射表：全部字段 TODO，不抛异常（宁缺勿编的极端面）")
    void emptyMappingsStillRenders() {
        String draft = generator.generateDraft("doc_empty", List.of());

        assertFalse(draft.contains("{{"));
        assertTrue(draft.contains("TODO 人工补充"));
    }

    private String firstPlaceholder(String s) {
        int i = s.indexOf("{{");
        return i < 0 ? "无" : s.substring(i, Math.min(i + 40, s.length()));
    }
}
