package com.lender.assistant.draft.service;

import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mock 草稿生成器：读 classpath 骨架模板，把映射表值逐占位符替换成草稿。
 *
 * 定位与 MockExtractGenerator 相同——key 没到位时验证
 * "映射表 → 代码草稿 → 人工复核提示"链路本身，不评估生成质量。
 *
 * 渲染规则（测试锁定）：
 * - URL/路径类字段：值原样进常量（假值比真值安全，全脱敏）
 * - status_enum：唯一需要代码整形的字段，值按 " / " 拆成 STATUS_MAP.put 行
 * - 缺失字段：渲染成 "// TODO 人工补充: 字段名"——宁缺勿编，
 *   草稿里宁可留 TODO 也不能编造一个看似正确的值
 */
@Component
@ConditionalOnProperty(name = "rag.draft.provider", havingValue = "mock", matchIfMissing = true)
public class MockDraftGenerator implements DraftGenerator {

    /**
     * 骨架单一来源：classpath 下的 draft/ExampleLenderHandler.java
     * （与 resources/draft/LenderHandler.java 一起构成模板素材，DeepSeek 版共用同一份）。
     * 加载失败属于部署错误，直接抛出去——渲染没有可降级的正确形态。
     */
    private static String loadSkeleton() {
        try (var in = MockDraftGenerator.class.getResourceAsStream("/draft/ExampleLenderHandler.java")) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 draft/ExampleLenderHandler.java 骨架");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("骨架模板读取失败", e);
        }
    }

    /** 骨架原文（DeepSeek 版拼 prompt 用，保持"模板单一来源"） */
    public String skeletonText() {
        return loadSkeleton();
    }

    @Override
    public String generateDraft(String docId, List<FieldMapping> mappings) {
        Map<String, String> byField = mappings.stream()
                .collect(Collectors.toMap(FieldMapping::internalFieldId, FieldMapping::lenderValue, (a, b) -> a));

        String lenderName = byField.getOrDefault("lender_name", docId);
        String out = loadSkeleton()
                .replace("{{LENDER_NAME}}", lenderName)
                .replace("{{LENDER_CODE}}", docId);

        // 占位符 → 字段值（或 TODO），逐个替换
        out = out.replace("{{F_GATEWAY_URL}}", valueOrTodo(byField, "gateway_url"))
                .replace("{{F_UAT_URL}}", valueOrTodo(byField, "uat_url"))
                .replace("{{F_PAYMENT_API}}", valueOrTodo(byField, "payment_api"))
                .replace("{{F_QUERY_API}}", valueOrTodo(byField, "query_api"))
                .replace("{{F_AUTH_METHOD}}", valueOrTodo(byField, "auth_method"))
                .replace("{{F_CREDENTIALS}}", valueOrTodo(byField, "credentials"))
                .replace("{{F_SIGN_RULE}}", valueOrTodo(byField, "sign_rule"))
                .replace("{{F_FILE_DELIVERY}}", valueOrTodo(byField, "file_delivery"))
                .replace("{{F_FILE_FORMAT}}", valueOrTodo(byField, "file_format"))
                .replace("{{F_STATUS_ENUM}}", statusEnumLines(byField));
        return out;
    }

    private String valueOrTodo(Map<String, String> byField, String fieldId) {
        String value = byField.get(fieldId);
        return value == null || value.isBlank()
                ? "TODO 人工补充: " + InternalFieldCatalog.byId(fieldId).name()
                : value;
    }

    /** "SUCCESS / FAILED / PROCESSING" → 三行 STATUS_MAP.put(...)；缺失给 TODO 行 */
    private String statusEnumLines(Map<String, String> byField) {
        String raw = byField.get("status_enum");
        if (raw == null || raw.isBlank()) {
            return "        // TODO 人工补充: 交易状态枚举";
        }
        return java.util.Arrays.stream(raw.split("\\s*/\\s*"))
                .filter(s -> !s.isBlank())
                .map(s -> "        STATUS_MAP.put(\"" + s.trim() + "\", \"" + s.trim() + "\"); // 人工核对映射关系")
                .collect(Collectors.joining("\n"));
    }

    @Override
    public String name() {
        return "Mock";
    }
}
