package com.lender.assistant.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 权限点目录契约：v1 固定 5 个码，落库值与页面勾选都以此为准。
 * 未知码必须拒绝——静默忽略会让「库里有的码后端不认识」形成第二套真相。
 */
class PermissionCodeTest {

    @Test
    @DisplayName("权限点目录固定为 5 个，码值与 PRD 一致")
    void catalogIsFixed() {
        List<String> codes = List.of(PermissionCode.values()).stream()
                .map(PermissionCode::code)
                .toList();
        assertEquals(List.of(
                "knowledge.documents.view",
                "knowledge.documents.write",
                "knowledge.chat.use",
                "settings.accounts.manage",
                "settings.roles.manage"), codes, "权限点码值不得漂移");
    }

    @Test
    @DisplayName("fromCode 只接受目录内的码")
    void fromCodeAcceptsKnownCodes() {
        assertEquals(PermissionCode.KNOWLEDGE_CHAT_USE,
                PermissionCode.fromCode("knowledge.chat.use"), "已知码应解析成功");
    }

    @Test
    @DisplayName("fromCode 拒绝未识别的码")
    void fromCodeRejectsUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> PermissionCode.fromCode("knowledge.documents.delete"),
                "未知权限码必须拒绝而不是静默忽略");
    }
}
