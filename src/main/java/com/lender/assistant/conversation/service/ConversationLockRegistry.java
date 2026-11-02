package com.lender.assistant.conversation.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话锁：保证单实例下同一会话同时只有一个回答在生成。
 *
 * 为什么不用 ReentrantLock：锁在请求线程获取、在工作线程释放，ReentrantLock 要求同线程解锁；
 * 用 ConcurrentHashMap 的 putIfAbsent / remove 做"占用标记"即可，语义就是一次性的 tryLock。
 *
 * 已知限制：多实例部署时 409 不成立（见 spec「事务边界与并发」）。
 */
@Component
public class ConversationLockRegistry {

    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /** 尝试占用会话；已被占用返回 false（调用方据此返回 409） */
    public boolean tryAcquire(String conversationId) {
        return busy.add(conversationId);
    }

    /** 释放占用；重复释放无副作用 */
    public void release(String conversationId) {
        busy.remove(conversationId);
    }

    /** 当前是否占用（测试与诊断用） */
    public boolean isBusy(String conversationId) {
        return busy.contains(conversationId);
    }
}
