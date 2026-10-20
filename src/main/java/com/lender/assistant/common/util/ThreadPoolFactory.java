package com.lender.assistant.common.util;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池静态工厂：把「命名 daemon 线程 + 有界队列 + 明确拒绝策略」这套 §8.1 硬约束收敛到一处，
 * 各处业务池只传参数，避免重复且易漏的样板（§3.6 单一事实源）。
 *
 * 设计取舍：
 * - 只负责创建、不负责生命周期：关停（@PreDestroy）留在持有该池的 Bean 里——
 *   工厂拿不到 Spring 生命周期，也不该替调用方决定何时停；
 * - 用 LinkedBlockingQueue 而非无界队列：容量占满即按 AbortPolicy 快速失败，
 *   避免任务无界堆积 OOM（§8.1 禁用 Executors 的根因正在于此）；
 * - 线程名带自增序号：同一前缀下的多实例线程可区分，便于 jstack / 日志排查；
 * - daemon 线程：不阻塞 JVM 退出。
 */
public final class ThreadPoolFactory {

    private ThreadPoolFactory() {
    }

    /**
     * 创建带命名线程 + 有界队列 + AbortPolicy 的线程池。
     *
     * @param coreSize         核心线程数
     * @param maxSize          最大线程数
     * @param queueCapacity    有界等待队列容量（占满即拒绝，不无界堆积）
     * @param keepAliveSeconds 空闲线程存活秒数
     * @param threadNamePrefix 线程名前缀；同前缀的多个实例以自增序号区分
     */
    public static ThreadPoolExecutor create(int coreSize, int maxSize, int queueCapacity,
                                            long keepAliveSeconds, String threadNamePrefix) {
        AtomicInteger sequence = new AtomicInteger(0);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(coreSize, maxSize, keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
