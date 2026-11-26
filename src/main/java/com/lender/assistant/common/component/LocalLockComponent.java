package com.lender.assistant.common.component;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内按键互斥标记（本地锁）：同一 key 同时只有一个持有者，语义即一次性 tryLock。
 *
 * 为什么不用 ReentrantLock：典型用法（会话锁）在请求线程获取、在工作线程释放，
 * ReentrantLock 要求同线程解锁；用 ConcurrentHashMap 的 add / remove 做占用标记即可。
 *
 * 调用方职责：
 * - key 必须带命名空间前缀防跨用途撞键（如 {@code "conversation:" + conversationId}），
 *   各领域的键前缀常量由领域自行收敛（会话锁见 MessageConstants.CONVERSATION_LOCK_PREFIX）；
 * - tryAcquire 失败不得 release（锁是别人持有的），只释放自己拿到的。
 *
 * 已知限制：仅单实例部署有效，多实例时互斥不成立——需要跨实例互斥时另行引入分布式锁。
 */
@Component
public class LocalLockComponent {

    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /** 尝试占用 key；已被占用返回 false */
    public boolean tryAcquire(String key) {
        return busy.add(key);
    }

    /** 释放占用；重复释放无副作用 */
    public void release(String key) {
        busy.remove(key);
    }

    /** 当前是否占用（测试与诊断用） */
    public boolean isBusy(String key) {
        return busy.contains(key);
    }
}
