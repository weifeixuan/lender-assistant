package com.lender.assistant.common.constant;

/**
 * message 表的角色与状态字面量（落库与 JSON 共用）。
 *
 * 收敛到一处：conversation 侧落库、历史装配与 rag 侧终态写入都引用这里，避免两套常量漂移。
 */
public final class MessageConstants {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_STOPPED = "STOPPED";

    private MessageConstants() {
    }
}
