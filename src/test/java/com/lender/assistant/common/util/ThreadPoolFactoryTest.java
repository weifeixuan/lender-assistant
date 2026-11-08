package com.lender.assistant.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolFactoryTest {

    @Test
    @DisplayName("createScheduled：单线程守护线程，线程名带业务前缀，便于排障与关停")
    void createScheduledUsesNamedDaemonThread() throws Exception {
        ScheduledExecutorService scheduler = ThreadPoolFactory.createScheduled("qa-test-timer");
        try {
            AtomicReference<Thread> worker = new AtomicReference<>();
            scheduler.schedule(() -> worker.set(Thread.currentThread()), 0, TimeUnit.MILLISECONDS)
                    .get(2, TimeUnit.SECONDS);

            assertTrue(worker.get().getName().startsWith("qa-test-timer-"),
                    "线程名应带业务前缀，实际: " + worker.get().getName());
            assertTrue(worker.get().isDaemon(),
                    "定时任务线程必须是 daemon，避免阻塞 JVM 退出");
        } finally {
            scheduler.shutdownNow();
        }
    }
}
