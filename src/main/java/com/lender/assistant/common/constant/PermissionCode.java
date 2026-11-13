package com.lender.assistant.common.constant;

/**
 * 权限点目录：v1 固定 5 个，是授权的唯一最小单位。
 *
 * 为什么写在代码里而不是建表：后端必须认识每个码才能在接口上校验；
 * 放进数据库会形成「库里有的码后端不认识」的第二套真相。
 * role_permission 落库值取 {@link #code()}，解析一律走 {@link #fromCode(String)}，
 * 未识别的码直接拒绝，不静默忽略。
 */
public enum PermissionCode {

    /** 查看文档：文档列表、切片、检索测试 */
    KNOWLEDGE_DOCUMENTS_VIEW("knowledge.documents.view", "查看文档"),
    /** 维护文档：上传、覆盖重建、删除文档 */
    KNOWLEDGE_DOCUMENTS_WRITE("knowledge.documents.write", "维护文档"),
    /** 使用知识库对话：对话页、/api/qa、会话列表/历史/删除 */
    KNOWLEDGE_CHAT_USE("knowledge.chat.use", "使用知识库对话"),
    /** 管理账户：账户管理页及其全部接口 */
    SETTINGS_ACCOUNTS_MANAGE("settings.accounts.manage", "管理账户"),
    /** 管理角色：角色管理页及其全部接口 */
    SETTINGS_ROLES_MANAGE("settings.roles.manage", "管理角色");

    private final String code;
    private final String desc;

    PermissionCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String code() {
        return code;
    }

    public String desc() {
        return desc;
    }

    /**
     * 按落库值解析权限点。
     *
     * @throws IllegalArgumentException 未识别的码：属于数据完整性问题，必须显式失败
     */
    public static PermissionCode fromCode(String code) {
        for (PermissionCode permission : values()) {
            if (permission.code.equals(code)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("未识别的权限点: " + code);
    }
}
