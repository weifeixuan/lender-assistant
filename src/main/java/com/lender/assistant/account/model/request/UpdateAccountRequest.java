package com.lender.assistant.account.model.request;

import java.util.List;

/**
 * 修改账户资料报文。角色集合整体替换，不能为空——多角色模型下必须能调整授权。
 */
public record UpdateAccountRequest(String displayName, List<String> roleIds) {
}
