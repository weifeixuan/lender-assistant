package com.lender.assistant.account.model.enums;

/**
 * 账户状态：落库值与比较口径唯一来源，避免各层散落字符串。
 */
public enum AccountStatus {

    ENABLED("ENABLED", "启用"),
    DISABLED("DISABLED", "禁用");

    private final String code;
    private final String desc;

    AccountStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String code() {
        return code;
    }

    public String desc() {
        return desc;
    }
}
