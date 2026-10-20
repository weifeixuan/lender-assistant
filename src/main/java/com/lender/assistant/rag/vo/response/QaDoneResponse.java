package com.lender.assistant.rag.vo.response;

/**
 * SSE "done" 事件载荷：问答收尾信号，前端据此展示 provider / 耗时 / 引用来源数。
 * 键名 provider / elapsedMillis / sourceCount 不变。
 */
public record QaDoneResponse(String provider, long elapsedMillis, int sourceCount) {
}
